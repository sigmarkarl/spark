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
ARG java_image_tag=14-slim
ARG spark_uid=3000

FROM openjdk:${java_image_tag}

# Before building the docker image, first build and make a Spark distribution following
# the instructions in http://spark.apache.org/docs/latest/building-spark.html.
# If this docker file is being used in the context of building your images from a Spark
# distribution, the docker build command should be invoked from the top level directory
# of the Spark distribution. E.g.:
# docker build -t spark:latest -f kubernetes/dockerfiles/spark/Dockerfile .

RUN set -ex && \
    sed -i 's/http:\/\/deb.\(.*\)/https:\/\/deb.\1/g' /etc/apt/sources.list && \
    apt-get update && \
    ln -s /lib /lib64 && \
    apt install -y bash tini libc6 libpam-modules krb5-user libnss3 procps && \
    mkdir -p /opt/spark && \
    mkdir -p /opt/spark/examples && \
    mkdir -p /opt/spark/work-dir && \
    touch /opt/spark/RELEASE && \
    rm /bin/sh && \
    ln -sv /bin/bash /bin/sh && \
    echo "auth required pam_wheel.so use_uid" >> /etc/pam.d/su && \
    chgrp root /etc/passwd && chmod ug+rw /etc/passwd && \
    rm -rf /var/cache/apt/*

COPY jars /opt/spark/jars
COPY bin /opt/spark/bin
COPY sbin /opt/spark/sbin
COPY resource-managers/kubernetes/docker/src/main/dockerfiles/spark/entrypoint.sh /opt/
COPY resource-managers/kubernetes/docker/src/main/dockerfiles/spark/decom.sh /opt/
#COPY examples /opt/spark/examples
#COPY kubernetes/tests /opt/spark/tests
COPY data /opt/spark/data

ENV SPARK_HOME /opt/spark

WORKDIR /opt/spark/work-dir
RUN chmod g+w /opt/spark/work-dir
RUN chmod a+x /opt/decom.sh

RUN rm -rf /opt/spark/jars/netty-all-4.0.23.Final.jar
RUN rm -rf /opt/spark/jars/jetty-6.1.26.jar
RUN rm -rf /opt/spark/jars/jetty-util-6.1.26.jar
RUN rm -rf /opt/spark/jars/jersey-client-1.19.jar
RUN rm -rf /opt/spark/jars/jersey-server-1.19.jar
RUN rm -rf /opt/spark/jars/log4j-over-slf4j-1.7.30.jar
#RUN rm -rf /opt/spark/jars/slf4j-api-1.7.30.jar
RUN rm -rf /opt/spark/jars/kubernetes-client-4.5.2.jar
RUN rm -rf /opt/spark/jars/kubernetes-model-4.5.2.jar
RUN rm -rf /opt/spark/jars/kubernetes-model-common-4.5.2.jar
RUN rm -rf /opt/spark/jars/logback-core-1.2.3.jar
RUN rm -rf /opt/spark/jars/logback-classic-1.2.3.jar

#RUN rm -rf /opt/spark/jars/hadoop-auth-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-annotations-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-common-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-hdfs-client-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-mapreduce-client-core-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-yarn-api-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-yarn-client-3.2.1.jar
#RUN rm -rf /opt/spark/jars/hadoop-yarn-common-3.2.1.jar

RUN rm -rf /opt/spark/jars/hadoop-client-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-mapreduce-client-app-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-mapreduce-client-common-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-mapreduce-client-jobclient-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-mapreduce-client-shuffle-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-yarn-server-common-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-yarn-server-nodemanager-2.7.4.jar
RUN rm -rf /opt/spark/jars/hadoop-hdfs-2.7.4.jar

RUN apt install -y ncbi-blast+

ENTRYPOINT [ "/opt/entrypoint.sh" ]

# Specify the User that the actual main process will run as
USER ${spark_uid}
