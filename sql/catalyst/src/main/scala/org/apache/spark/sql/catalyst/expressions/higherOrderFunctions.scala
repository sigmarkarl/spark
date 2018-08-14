/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.{TypeCheckResult, TypeCoercion, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.array.ByteArrayMethods

/**
 * A named lambda variable.
 */
case class NamedLambdaVariable(
    name: String,
    dataType: DataType,
    nullable: Boolean,
    exprId: ExprId = NamedExpression.newExprId,
    value: AtomicReference[Any] = new AtomicReference())
  extends LeafExpression
  with NamedExpression
  with CodegenFallback {

  override def qualifier: Seq[String] = Seq.empty

  override def newInstance(): NamedExpression =
    copy(exprId = NamedExpression.newExprId, value = new AtomicReference())

  override def toAttribute: Attribute = {
    AttributeReference(name, dataType, nullable, Metadata.empty)(exprId, Seq.empty)
  }

  override def eval(input: InternalRow): Any = value.get

  override def toString: String = s"lambda $name#${exprId.id}$typeSuffix"

  override def simpleString: String = s"lambda $name#${exprId.id}: ${dataType.simpleString}"
}

/**
 * A lambda function and its arguments. A lambda function can be hidden when a user wants to
 * process an completely independent expression in a [[HigherOrderFunction]], the lambda function
 * and its variables are then only used for internal bookkeeping within the higher order function.
 */
case class LambdaFunction(
    function: Expression,
    arguments: Seq[NamedExpression],
    hidden: Boolean = false)
  extends Expression with CodegenFallback {

  override def children: Seq[Expression] = function +: arguments
  override def dataType: DataType = function.dataType
  override def nullable: Boolean = function.nullable

  lazy val bound: Boolean = arguments.forall(_.resolved)

  override def eval(input: InternalRow): Any = function.eval(input)
}

object LambdaFunction {
  val identity: LambdaFunction = {
    val id = UnresolvedAttribute.quoted("id")
    LambdaFunction(id, Seq(id))
  }
}

/**
 * A higher order function takes one or more (lambda) functions and applies these to some objects.
 * The function produces a number of variables which can be consumed by some lambda function.
 */
trait HigherOrderFunction extends Expression with ExpectsInputTypes {

  override def children: Seq[Expression] = arguments ++ functions

  /**
   * Arguments of the higher ordered function.
   */
  def arguments: Seq[Expression]

  def argumentTypes: Seq[AbstractDataType]

  /**
   * All arguments have been resolved. This means that the types and nullabilty of (most of) the
   * lambda function arguments is known, and that we can start binding the lambda functions.
   */
  lazy val argumentsResolved: Boolean = arguments.forall(_.resolved)

  /**
   * Checks the argument data types, returns `TypeCheckResult.success` if it's valid,
   * or returns a `TypeCheckResult` with an error message if invalid.
   * Note: it's not valid to call this method until `argumentsResolved == true`.
   */
  def checkArgumentDataTypes(): TypeCheckResult = {
    ExpectsInputTypes.checkInputDataTypes(arguments, argumentTypes)
  }

  /**
   * Functions applied by the higher order function.
   */
  def functions: Seq[Expression]

  def functionTypes: Seq[AbstractDataType]

  override def inputTypes: Seq[AbstractDataType] = argumentTypes ++ functionTypes

  /**
   * All inputs must be resolved and all functions must be resolved lambda functions.
   */
  override lazy val resolved: Boolean = argumentsResolved && functions.forall {
    case l: LambdaFunction => l.resolved
    case _ => false
  }

  /**
   * Bind the lambda functions to the [[HigherOrderFunction]] using the given bind function. The
   * bind function takes the potential lambda and it's (partial) arguments and converts this into
   * a bound lambda function.
   */
  def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): HigherOrderFunction

  // Make sure the lambda variables refer the same instances as of arguments for case that the
  // variables in instantiated separately during serialization or for some reason.
  @transient lazy val functionsForEval: Seq[Expression] = functions.map {
    case LambdaFunction(function, arguments, hidden) =>
      val argumentMap = arguments.map { arg => arg.exprId -> arg }.toMap
      function.transformUp {
        case variable: NamedLambdaVariable if argumentMap.contains(variable.exprId) =>
          argumentMap(variable.exprId)
      }
  }
}

/**
 * Trait for functions having as input one argument and one function.
 */
trait SimpleHigherOrderFunction extends HigherOrderFunction  {

  def argument: Expression

  override def arguments: Seq[Expression] = argument :: Nil

  def argumentType: AbstractDataType

  override def argumentTypes(): Seq[AbstractDataType] = argumentType :: Nil

  def function: Expression

  override def functions: Seq[Expression] = function :: Nil

  def functionType: AbstractDataType = AnyDataType

  override def functionTypes: Seq[AbstractDataType] = functionType :: Nil

  def functionForEval: Expression = functionsForEval.head

  /**
   * Called by [[eval]]. If a subclass keeps the default nullability, it can override this method
   * in order to save null-check code.
   */
  protected def nullSafeEval(inputRow: InternalRow, argumentValue: Any): Any =
    sys.error(s"UnaryHigherOrderFunction must override either eval or nullSafeEval")

  override def eval(inputRow: InternalRow): Any = {
    val value = argument.eval(inputRow)
    if (value == null) {
      null
    } else {
      nullSafeEval(inputRow, value)
    }
  }
}

trait ArrayBasedSimpleHigherOrderFunction extends SimpleHigherOrderFunction {
  override def argumentType: AbstractDataType = ArrayType
}

trait MapBasedSimpleHigherOrderFunction extends SimpleHigherOrderFunction {
  override def argumentType: AbstractDataType = MapType
}

/**
 * Transform elements in an array using the transform function. This is similar to
 * a `map` in functional programming.
 */
@ExpressionDescription(
  usage = "_FUNC_(expr, func) - Transforms elements in an array using the function.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 2, 3), x -> x + 1);
       array(2, 3, 4)
      > SELECT _FUNC_(array(1, 2, 3), (x, i) -> x + i);
       array(1, 3, 5)
  """,
  since = "2.4.0")
case class ArrayTransform(
    argument: Expression,
    function: Expression)
  extends ArrayBasedSimpleHigherOrderFunction with CodegenFallback {

  override def nullable: Boolean = argument.nullable

  override def dataType: ArrayType = ArrayType(function.dataType, function.nullable)

  override def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): ArrayTransform = {
    val ArrayType(elementType, containsNull) = argument.dataType
    function match {
      case LambdaFunction(_, arguments, _) if arguments.size == 2 =>
        copy(function = f(function, (elementType, containsNull) :: (IntegerType, false) :: Nil))
      case _ =>
        copy(function = f(function, (elementType, containsNull) :: Nil))
    }
  }

  @transient lazy val (elementVar, indexVar) = {
    val LambdaFunction(_, (elementVar: NamedLambdaVariable) +: tail, _) = function
    val indexVar = if (tail.nonEmpty) {
      Some(tail.head.asInstanceOf[NamedLambdaVariable])
    } else {
      None
    }
    (elementVar, indexVar)
  }

  override def nullSafeEval(inputRow: InternalRow, argumentValue: Any): Any = {
    val arr = argumentValue.asInstanceOf[ArrayData]
    val f = functionForEval
    val result = new GenericArrayData(new Array[Any](arr.numElements))
    var i = 0
    while (i < arr.numElements) {
      elementVar.value.set(arr.get(i, elementVar.dataType))
      if (indexVar.isDefined) {
        indexVar.get.value.set(i)
      }
      result.update(i, f.eval(inputRow))
      i += 1
    }
    result
  }

  override def prettyName: String = "transform"
}

/**
 * Filters entries in a map using the provided function.
 */
@ExpressionDescription(
usage = "_FUNC_(expr, func) - Filters entries in a map using the function.",
examples = """
    Examples:
      > SELECT _FUNC_(map(1, 0, 2, 2, 3, -1), (k, v) -> k > v);
       [1 -> 0, 3 -> -1]
  """,
since = "2.4.0")
case class MapFilter(
    argument: Expression,
    function: Expression)
  extends MapBasedSimpleHigherOrderFunction with CodegenFallback {

  @transient lazy val (keyVar, valueVar) = {
    val args = function.asInstanceOf[LambdaFunction].arguments
    (args.head.asInstanceOf[NamedLambdaVariable], args.tail.head.asInstanceOf[NamedLambdaVariable])
  }

  @transient lazy val MapType(keyType, valueType, valueContainsNull) = argument.dataType

  override def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): MapFilter = {
    copy(function = f(function, (keyType, false) :: (valueType, valueContainsNull) :: Nil))
  }

  override def nullable: Boolean = argument.nullable

  override def nullSafeEval(inputRow: InternalRow, argumentValue: Any): Any = {
    val m = argumentValue.asInstanceOf[MapData]
    val f = functionForEval
    val retKeys = new mutable.ListBuffer[Any]
    val retValues = new mutable.ListBuffer[Any]
    m.foreach(keyType, valueType, (k, v) => {
      keyVar.value.set(k)
      valueVar.value.set(v)
      if (f.eval(inputRow).asInstanceOf[Boolean]) {
        retKeys += k
        retValues += v
      }
    })
    ArrayBasedMapData(retKeys.toArray, retValues.toArray)
  }

  override def dataType: DataType = argument.dataType

  override def functionType: AbstractDataType = BooleanType

  override def prettyName: String = "map_filter"
}

/**
 * Filters the input array using the given lambda function.
 */
@ExpressionDescription(
  usage = "_FUNC_(expr, func) - Filters the input array using the given predicate.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 2, 3), x -> x % 2 == 1);
       array(1, 3)
  """,
  since = "2.4.0")
case class ArrayFilter(
    argument: Expression,
    function: Expression)
  extends ArrayBasedSimpleHigherOrderFunction with CodegenFallback {

  override def nullable: Boolean = argument.nullable

  override def dataType: DataType = argument.dataType

  override def functionType: AbstractDataType = BooleanType

  override def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): ArrayFilter = {
    val ArrayType(elementType, containsNull) = argument.dataType
    copy(function = f(function, (elementType, containsNull) :: Nil))
  }

  @transient lazy val LambdaFunction(_, Seq(elementVar: NamedLambdaVariable), _) = function

  override def nullSafeEval(inputRow: InternalRow, argumentValue: Any): Any = {
    val arr = argumentValue.asInstanceOf[ArrayData]
    val f = functionForEval
    val buffer = new mutable.ArrayBuffer[Any](arr.numElements)
    var i = 0
    while (i < arr.numElements) {
      elementVar.value.set(arr.get(i, elementVar.dataType))
      if (f.eval(inputRow).asInstanceOf[Boolean]) {
        buffer += elementVar.value.get
      }
      i += 1
    }
    new GenericArrayData(buffer)
  }

  override def prettyName: String = "filter"
}

/**
 * Tests whether a predicate holds for one or more elements in the array.
 */
@ExpressionDescription(usage =
  "_FUNC_(expr, pred) - Tests whether a predicate holds for one or more elements in the array.",
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 2, 3), x -> x % 2 == 0);
       true
  """,
  since = "2.4.0")
case class ArrayExists(
    argument: Expression,
    function: Expression)
  extends ArrayBasedSimpleHigherOrderFunction with CodegenFallback {

  override def nullable: Boolean = argument.nullable

  override def dataType: DataType = BooleanType

  override def functionType: AbstractDataType = BooleanType

  override def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): ArrayExists = {
    val ArrayType(elementType, containsNull) = argument.dataType
    copy(function = f(function, (elementType, containsNull) :: Nil))
  }

  @transient lazy val LambdaFunction(_, Seq(elementVar: NamedLambdaVariable), _) = function

  override def nullSafeEval(inputRow: InternalRow, argumentValue: Any): Any = {
    val arr = argumentValue.asInstanceOf[ArrayData]
    val f = functionForEval
    var exists = false
    var i = 0
    while (i < arr.numElements && !exists) {
      elementVar.value.set(arr.get(i, elementVar.dataType))
      if (f.eval(inputRow).asInstanceOf[Boolean]) {
        exists = true
      }
      i += 1
    }
    exists
  }

  override def prettyName: String = "exists"
}

/**
 * Applies a binary operator to a start value and all elements in the array.
 */
@ExpressionDescription(
  usage =
    """
      _FUNC_(expr, start, merge, finish) - Applies a binary operator to an initial state and all
      elements in the array, and reduces this to a single state. The final state is converted
      into the final result by applying a finish function.
    """,
  examples = """
    Examples:
      > SELECT _FUNC_(array(1, 2, 3), 0, (acc, x) -> acc + x);
       6
      > SELECT _FUNC_(array(1, 2, 3), 0, (acc, x) -> acc + x, acc -> acc * 10);
       60
  """,
  since = "2.4.0")
case class ArrayAggregate(
    argument: Expression,
    zero: Expression,
    merge: Expression,
    finish: Expression)
  extends HigherOrderFunction with CodegenFallback {

  def this(argument: Expression, zero: Expression, merge: Expression) = {
    this(argument, zero, merge, LambdaFunction.identity)
  }

  override def arguments: Seq[Expression] = argument :: zero :: Nil

  override def argumentTypes: Seq[AbstractDataType] = ArrayType :: AnyDataType :: Nil

  override def functions: Seq[Expression] = merge :: finish :: Nil

  override def functionTypes: Seq[AbstractDataType] = zero.dataType :: AnyDataType :: Nil

  override def nullable: Boolean = argument.nullable || finish.nullable

  override def dataType: DataType = finish.dataType

  override def checkInputDataTypes(): TypeCheckResult = {
    checkArgumentDataTypes() match {
      case TypeCheckResult.TypeCheckSuccess =>
        if (!DataType.equalsStructurally(
            zero.dataType, merge.dataType, ignoreNullability = true)) {
          TypeCheckResult.TypeCheckFailure(
            s"argument 3 requires ${zero.dataType.simpleString} type, " +
              s"however, '${merge.sql}' is of ${merge.dataType.catalogString} type.")
        } else {
          TypeCheckResult.TypeCheckSuccess
        }
      case failure => failure
    }
  }

  override def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): ArrayAggregate = {
    // Be very conservative with nullable. We cannot be sure that the accumulator does not
    // evaluate to null. So we always set nullable to true here.
    val ArrayType(elementType, containsNull) = argument.dataType
    val acc = zero.dataType -> true
    val newMerge = f(merge, acc :: (elementType, containsNull) :: Nil)
    val newFinish = f(finish, acc :: Nil)
    copy(merge = newMerge, finish = newFinish)
  }

  @transient lazy val LambdaFunction(_,
    Seq(accForMergeVar: NamedLambdaVariable, elementVar: NamedLambdaVariable), _) = merge
  @transient lazy val LambdaFunction(_, Seq(accForFinishVar: NamedLambdaVariable), _) = finish

  override def eval(input: InternalRow): Any = {
    val arr = argument.eval(input).asInstanceOf[ArrayData]
    if (arr == null) {
      null
    } else {
      val Seq(mergeForEval, finishForEval) = functionsForEval
      accForMergeVar.value.set(zero.eval(input))
      var i = 0
      while (i < arr.numElements()) {
        elementVar.value.set(arr.get(i, elementVar.dataType))
        accForMergeVar.value.set(mergeForEval.eval(input))
        i += 1
      }
      accForFinishVar.value.set(accForMergeVar.value.get)
      finishForEval.eval(input)
    }
  }

  override def prettyName: String = "aggregate"
}

/**
 * Merges two given maps into a single map by applying function to the pair of values with
 * the same key.
 */
@ExpressionDescription(
  usage =
    """
      _FUNC_(map1, map2, function) - Merges two given maps into a single map by applying
      function to the pair of values with the same key. For keys only presented in one map,
      NULL will be passed as the value for the missing key. If an input map contains duplicated
      keys, only the first entry of the duplicated key is passed into the lambda function.
    """,
  examples = """
    Examples:
      > SELECT _FUNC_(map(1, 'a', 2, 'b'), map(1, 'x', 2, 'y'), (k, v1, v2) -> concat(v1, v2));
       {1:"ax",2:"by"}
  """,
  since = "2.4.0")
case class MapZipWith(left: Expression, right: Expression, function: Expression)
  extends HigherOrderFunction with CodegenFallback {

  def functionForEval: Expression = functionsForEval.head

  @transient lazy val MapType(leftKeyType, leftValueType, leftValueContainsNull) = left.dataType

  @transient lazy val MapType(rightKeyType, rightValueType, rightValueContainsNull) = right.dataType

  @transient lazy val keyType =
    TypeCoercion.findCommonTypeDifferentOnlyInNullFlags(leftKeyType, rightKeyType).get

  @transient lazy val ordering = TypeUtils.getInterpretedOrdering(keyType)

  override def arguments: Seq[Expression] = left :: right :: Nil

  override def argumentTypes: Seq[AbstractDataType] = MapType :: MapType :: Nil

  override def functions: Seq[Expression] = function :: Nil

  override def functionTypes: Seq[AbstractDataType] = AnyDataType :: Nil

  override def nullable: Boolean = left.nullable || right.nullable

  override def dataType: DataType = MapType(keyType, function.dataType, function.nullable)

  override def bind(f: (Expression, Seq[(DataType, Boolean)]) => LambdaFunction): MapZipWith = {
    val arguments = Seq((keyType, false), (leftValueType, true), (rightValueType, true))
    copy(function = f(function, arguments))
  }

  override def checkArgumentDataTypes(): TypeCheckResult = {
    super.checkArgumentDataTypes() match {
      case TypeCheckResult.TypeCheckSuccess =>
        if (leftKeyType.sameType(rightKeyType)) {
          TypeUtils.checkForOrderingExpr(leftKeyType, s"function $prettyName")
        } else {
          TypeCheckResult.TypeCheckFailure(s"The input to function $prettyName should have " +
            s"been two ${MapType.simpleString}s with compatible key types, but the key types are " +
            s"[${leftKeyType.catalogString}, ${rightKeyType.catalogString}].")
        }
      case failure => failure
    }
  }

  override def checkInputDataTypes(): TypeCheckResult = checkArgumentDataTypes()

  override def eval(input: InternalRow): Any = {
    val value1 = left.eval(input)
    if (value1 == null) {
      null
    } else {
      val value2 = right.eval(input)
      if (value2 == null) {
        null
      } else {
        nullSafeEval(input, value1, value2)
      }
    }
  }

  @transient lazy val LambdaFunction(_, Seq(
    keyVar: NamedLambdaVariable,
    value1Var: NamedLambdaVariable,
    value2Var: NamedLambdaVariable),
    _) = function

  private def keyTypeSupportsEquals = keyType match {
    case BinaryType => false
    case _: AtomicType => true
    case _ => false
  }

  /**
   * The function accepts two key arrays and returns a collection of keys with indexes
   * to value arrays. Indexes are represented as an array of two items. This is a small
   * optimization leveraging mutability of arrays.
   */
  @transient private lazy val getKeysWithValueIndexes:
      (ArrayData, ArrayData) => mutable.Iterable[(Any, Array[Option[Int]])] = {
    if (keyTypeSupportsEquals) {
      getKeysWithIndexesFast
    } else {
      getKeysWithIndexesBruteForce
    }
  }

  private def assertSizeOfArrayBuffer(size: Int): Unit = {
    if (size > ByteArrayMethods.MAX_ROUNDED_ARRAY_LENGTH) {
      throw new RuntimeException(s"Unsuccessful try to zip maps with $size " +
        s"unique keys due to exceeding the array size limit " +
        s"${ByteArrayMethods.MAX_ROUNDED_ARRAY_LENGTH}.")
    }
  }

  private def getKeysWithIndexesFast(keys1: ArrayData, keys2: ArrayData) = {
    val hashMap = new mutable.LinkedHashMap[Any, Array[Option[Int]]]
    for((z, array) <- Array((0, keys1), (1, keys2))) {
      var i = 0
      while (i < array.numElements()) {
        val key = array.get(i, keyType)
        hashMap.get(key) match {
          case Some(indexes) =>
            if (indexes(z).isEmpty) {
              indexes(z) = Some(i)
            }
          case None =>
            val indexes = Array[Option[Int]](None, None)
            indexes(z) = Some(i)
            hashMap.put(key, indexes)
        }
        i += 1
      }
    }
    hashMap
  }

  private def getKeysWithIndexesBruteForce(keys1: ArrayData, keys2: ArrayData) = {
    val arrayBuffer = new mutable.ArrayBuffer[(Any, Array[Option[Int]])]
    for((z, array) <- Array((0, keys1), (1, keys2))) {
      var i = 0
      while (i < array.numElements()) {
        val key = array.get(i, keyType)
        var found = false
        var j = 0
        while (!found && j < arrayBuffer.size) {
          val (bufferKey, indexes) = arrayBuffer(j)
          if (ordering.equiv(bufferKey, key)) {
            found = true
            if(indexes(z).isEmpty) {
              indexes(z) = Some(i)
            }
          }
          j += 1
        }
        if (!found) {
          assertSizeOfArrayBuffer(arrayBuffer.size)
          val indexes = Array[Option[Int]](None, None)
          indexes(z) = Some(i)
          arrayBuffer += Tuple2(key, indexes)
        }
        i += 1
      }
    }
    arrayBuffer
  }

  private def nullSafeEval(inputRow: InternalRow, value1: Any, value2: Any): Any = {
    val mapData1 = value1.asInstanceOf[MapData]
    val mapData2 = value2.asInstanceOf[MapData]
    val keysWithIndexes = getKeysWithValueIndexes(mapData1.keyArray(), mapData2.keyArray())
    val size = keysWithIndexes.size
    val keys = new GenericArrayData(new Array[Any](size))
    val values = new GenericArrayData(new Array[Any](size))
    val valueData1 = mapData1.valueArray()
    val valueData2 = mapData2.valueArray()
    var i = 0
    for ((key, Array(index1, index2)) <- keysWithIndexes) {
      val v1 = index1.map(valueData1.get(_, leftValueType)).getOrElse(null)
      val v2 = index2.map(valueData2.get(_, rightValueType)).getOrElse(null)
      keyVar.value.set(key)
      value1Var.value.set(v1)
      value2Var.value.set(v2)
      keys.update(i, key)
      values.update(i, functionForEval.eval(inputRow))
      i += 1
    }
    new ArrayBasedMapData(keys, values)
  }

  override def prettyName: String = "map_zip_with"
}
