#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from pyspark.resource.taskrequests import TaskResourceRequest
from pyspark.util import _parse_memory


class ExecutorResourceRequest(object):
    """
    .. note:: Evolving

    An Executor resource request. This is used in conjunction with the ResourceProfile to
    programmatically specify the resources needed for an RDD that will be applied at the
    stage level.

    This is used to specify what the resource requirements are for an Executor and how
    Spark can find out specific details about those resources. Not all the parameters are
    required for every resource type. Resources like GPUs are supported and have same limitations
    as using the global spark configs spark.executor.resource.gpu.*. The amount, discoveryScript,
    and vendor parameters for resources are all the same parameters a user would specify through the
    configs: spark.executor.resource.{resourceName}.{amount, discoveryScript, vendor}.

    For instance, a user wants to allocate an Executor with GPU resources on YARN. The user has
    to specify the resource name (gpu), the amount or number of GPUs per Executor,
    the discovery script would be specified so that when the Executor starts up it can
    discovery what GPU addresses are available for it to use because YARN doesn't tell
    Spark that, then vendor would not be used because its specific for Kubernetes.

    See the configuration and cluster specific docs for more details.

    Use `pyspark.ExecutorResourceRequests` class as a convenience API.

    :param resourceName: Name of the resource
    :param amount: Amount requesting
    :param discoveryScript: Optional script used to discover the resources. This is required on some
        cluster managers that don't tell Spark the addresses of the resources
        allocated. The script runs on Executors startup to discover the addresses
        of the resources available.
    :param vendor: Vendor, required for some cluster managers

    .. versionadded:: 3.1.0
    """
    def __init__(self, resourceName, amount, discoveryScript="", vendor=""):
        self._name = resourceName
        self._amount = amount
        self._discovery_script = discoveryScript
        self._vendor = vendor

    @property
    def resourceName(self):
        return self._name

    @property
    def amount(self):
        return self._amount

    @property
    def discoveryScript(self):
        return self._discovery_script

    @property
    def vendor(self):
        return self._vendor


class ExecutorResourceRequests(object):

    """
    .. note:: Evolving

    A set of Executor resource requests. This is used in conjunction with the
    :class:`pyspark.resource.ResourceProfileBuilder` to programmatically specify the
    resources needed for an RDD that will be applied at the stage level.

    .. versionadded:: 3.1.0
    """
    _CORES = "cores"
    _MEMORY = "memory"
    _OVERHEAD_MEM = "memoryOverhead"
    _PYSPARK_MEM = "pyspark.memory"

    def __init__(self, _jvm=None, _requests=None):
        from pyspark import SparkContext
        _jvm = _jvm or SparkContext._jvm
        if _jvm is not None:
            self._java_executor_resource_requests = \
                _jvm.org.apache.spark.resource.ExecutorResourceRequests()
            if _requests is not None:
                for k, v in _requests.items():
                    if k == self._MEMORY:
                        self._java_executor_resource_requests.memory(str(v.amount))
                    elif k == self._OVERHEAD_MEM:
                        self._java_executor_resource_requests.memoryOverhead(str(v.amount))
                    elif k == self._PYSPARK_MEM:
                        self._java_executor_resource_requests.pysparkMemory(str(v.amount))
                    elif k == self._CORES:
                        self._java_executor_resource_requests.cores(v.amount)
                    else:
                        self._java_executor_resource_requests.resource(v.resourceName, v.amount,
                                                                       v.discoveryScript, v.vendor)
        else:
            self._java_executor_resource_requests = None
            self._executor_resources = {}

    def memory(self, amount):
        if self._java_executor_resource_requests is not None:
            self._java_executor_resource_requests.memory(amount)
        else:
            self._executor_resources[self._MEMORY] = ExecutorResourceRequest(self._MEMORY,
                                                                             _parse_memory(amount))
        return self

    def memoryOverhead(self, amount):
        if self._java_executor_resource_requests is not None:
            self._java_executor_resource_requests.memoryOverhead(amount)
        else:
            self._executor_resources[self._OVERHEAD_MEM] = \
                ExecutorResourceRequest(self._OVERHEAD_MEM, _parse_memory(amount))
        return self

    def pysparkMemory(self, amount):
        if self._java_executor_resource_requests is not None:
            self._java_executor_resource_requests.pysparkMemory(amount)
        else:
            self._executor_resources[self._PYSPARK_MEM] = \
                ExecutorResourceRequest(self._PYSPARK_MEM, _parse_memory(amount))
        return self

    def cores(self, amount):
        if self._java_executor_resource_requests is not None:
            self._java_executor_resource_requests.cores(amount)
        else:
            self._executor_resources[self._CORES] = ExecutorResourceRequest(self._CORES, amount)
        return self

    def resource(self, resourceName, amount, discoveryScript="", vendor=""):
        if self._java_executor_resource_requests is not None:
            self._java_executor_resource_requests.resource(resourceName, amount, discoveryScript,
                                                           vendor)
        else:
            self._executor_resources[resourceName] = \
                ExecutorResourceRequest(resourceName, amount, discoveryScript, vendor)
        return self

    @property
    def requests(self):
        if self._java_executor_resource_requests is not None:
            result = {}
            execRes = self._java_executor_resource_requests.requestsJMap()
            for k, v in execRes.items():
                result[k] = ExecutorResourceRequest(v.resourceName(), v.amount(),
                                                    v.discoveryScript(), v.vendor())
            return result
        else:
            return self._executor_resources
