FROM ubuntu:20.04

ARG spark_uid=3000

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    SPARK_HOME=/opt/spark \
    R_HOME=/usr/lib/R \
    PYSPARK_PYTHON=/usr/bin/python3 \
    PYSPARK_DRIVER_PYTHON=/usr/bin/python3 \
    HAIL_DIR=/usr/local/lib/python3.8/dist-packages/hail/

RUN apt -y update && \
    apt install -y locales && \
    locale-gen $LANG && \
    apt install -y libpam-modules krb5-user libnss3 && \
    apt install -y build-essential && \
    apt install -y git rsync && \
    apt install -y curl liblz4-dev && \
    apt install -y tini openjdk-14-jdk-headless && \
    apt install -y python3 python3-setuptools python3-pip && \
    apt install -y python3-decorator python3-pandas python3-requests python3-numpy python3-scipy

RUN mkdir -p ${SPARK_HOME} && \
    mkdir -p ${SPARK_HOME}/python && \
    mkdir -p ${SPARK_HOME}/examples && \
    mkdir -p ${SPARK_HOME}/work-dir && \
    touch /opt/spark/RELEASE && \
    echo "auth required pam_wheel.so use_uid" >> /etc/pam.d/su && \
    chgrp root /etc/passwd && chmod ug+rw /etc/passwd && \
    rm -rf /var/cache/apt/*

RUN apt install -y r-base r-base-dev
RUN apt install -y ncbi-blast+
RUN pip3 install py4j parsimonious bokeh glow.py bioinfokit pyarrow
RUN pip3 install --upgrade pandas

#RUN apt install -y openjdk-11-jdk-headless && \
#    update-alternatives --set java /usr/lib/jvm/java-11-openjdk-amd64/bin/java

#RUN ln -s /usr/lib/jvm/java-11-openjdk-amd64/include /usr/lib/jvm/include && \
#    ln -s /usr/lib/jvm/java-11-openjdk-amd64/lib /usr/lib/jvm/lib

#RUN git clone https://github.com/sigmarkarl/hail.git && \
#    cd hail/hail && \
#    make install-on-cluster HAIL_COMPILE_NATIVES=1 SPARK_VERSION=3.0.0

COPY python/pyspark ${SPARK_HOME}/python/pyspark
COPY python/lib ${SPARK_HOME}/python/lib
COPY R ${SPARK_HOME}/R

COPY jars /opt/spark/jars
#COPY gor-scripts/lib /opt/spark/jars
#COPY assembly/target/scala-2.12/jars /opt/spark/jars
COPY bin /opt/spark/bin
COPY sbin /opt/spark/sbin
#COPY entrypoint.sh /opt/
COPY resource-managers/kubernetes/docker/src/main/dockerfiles/spark/entrypoint.sh /opt/
COPY examples /opt/spark/examples
COPY resource-managers/kubernetes/integration-tests/tests /opt/spark/tests
COPY data /opt/spark/data

WORKDIR /opt/spark/work-dir 
RUN chmod g+w /opt/spark/work-dir

RUN rm -rf /opt/spark/jars/netty-all-4.0.23.Final.jar
RUN rm -rf /opt/spark/jars/jetty-6.1.26.jar
RUN rm -rf /opt/spark/jars/jetty-util-6.1.26.jar
RUN rm -rf /opt/spark/jars/jersey-client-1.19.jar
RUN rm -rf /opt/spark/jars/jersey-server-1.19.jar
RUN rm -rf /opt/spark/jars/log4j-over-slf4j-1.7.30.jar
RUN rm -rf /opt/spark/jars/kubernetes-client-4.5.2.jar
RUN rm -rf /opt/spark/jars/kubernetes-model-4.5.2.jar
RUN rm -rf /opt/spark/jars/kubernetes-model-common-4.5.2.jar
RUN rm -rf /opt/spark/jars/logback-core-1.2.3.jar
RUN rm -rf /opt/spark/jars/logback-classic-1.2.3.jar
#RUN rm -rf /opt/spark/jars/univocity-parsers-2.8.3.jar  

#RUN rm -rf /opt/spark/jars/breeze_2.12-0.13.2.jar
#RUN rm -rf /opt/spark/jars/breeze-macros_2.12-0.13.2.jar

#RUN cp /usr/local/lib/python3.8/dist-packages/hail/backend/hail-all-spark.jar /opt/spark/jars
#RUN update-alternatives --set java /usr/lib/jvm/java-14-openjdk-amd64/bin/java

#COPY slf4j-log4j12-1.7.16.jar /opt/spark/jars/
#COPY log4j-1.2.17.jar /opt/spark/jars/
#COPY spark-repl_2.12-3.0.0-preview2.jar  /opt/spark/jars/
#COPY gorservices-spark-9.6-SNAPSHOT.jar /opt/spark/jars/
#COPY gor-gortools-8.1-SNAPSHOT.jar /opt/spark/jars/
#COPY gor-model-8.1-SNAPSHOT.jar /opt/spark/jars/

ENTRYPOINT [ "/opt/entrypoint.sh" ]

#USER ${spark_uid}