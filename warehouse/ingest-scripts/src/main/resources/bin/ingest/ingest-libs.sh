#!/bin/bash

# Get environment
. ../ingest/ingest-env.sh

findJar (){
  ls -1 ../../lib/$1-[0-9]*.jar | sort | tail -1
}
findFirstJar (){
  ls -1 ../../lib/$1-[0-9]*.jar | sort | head -1
}
findAllJars (){
  ls -1 ../../lib/$1-[0-9]*.jar | sort | paste -sd ':' -
}
findWebserviceJar (){
  ls -1 ../../lib/$1[-0-9.]*jar | sort | head -1
}
findProvenanceJar (){
  ls -1 ../../lib/$1-[0-9.]*.*.jar |  grep -v with-dependencies | sort | tail -1
}

CONF_DIR=../../config
DATAWAVE_INGEST_CSV_JAR=../../lib/datawave-ingest-csv-$INGEST_VERSION.jar
DATAWAVE_INGEST_JSON_JAR=../../lib/datawave-ingest-json-$INGEST_VERSION.jar
DATAWAVE_INGEST_WIKIPEDIA_JAR=../../lib/datawave-ingest-wikipedia-$INGEST_VERSION.jar
DATAWAVE_INGEST_CORE_JAR=../../lib/datawave-ingest-core-$INGEST_VERSION.jar
DATAWAVE_INGEST_CONFIG_JAR=../../lib/datawave-ingest-configuration-$INGEST_VERSION.jar
DATAWAVE_COMMON_JAR=../../lib/datawave-common-$INGEST_VERSION.jar
DATAWAVE_BALANCERS_JAR=../../lib/datawave-balancers-$INGEST_VERSION.jar
COMMON_UTIL_JAR=$(findWebserviceJar datawave-ws-common-util)
COMMON_JAR=$(findWebserviceJar datawave-ws-common)
DATAWAVE_CORE_JAR=$(findJar datawave-core)
DATAWAVE_WS_QUERY_JAR=$(findWebserviceJar datawave-ws-query)
DATAWAVE_WS_CLIENT_JAR=$(findWebserviceJar datawave-ws-client)
CURATOR_FRAMEWORK_JAR=$(findJar curator-framework)
CURATOR_UTILS_JAR=$(findJar curator-client)
COMMONS_LANG_JAR=$(findJar commons-lang)
COMMONS_LANG3_JAR=$(findJar commons-lang3)
COMMONS_COLLECTIONS_JAR=$(findJar commons-collections4)
COMMONS_CONFIGURATION_JAR=$(findJar commons-configuration)
AVRO_JAR=$(findJar avro)
HTTPCLIENT_JAR=$(findJar httpclient)
HTTPCORE_JAR=$(findJar httpcore)
COMMONS_IO_JAR=$(findJar commons-io)
STREAMLIB=$(findJar stream)
COMMONS_POOL_JAR=$(findJar commons-pool)
COMMONS_JCI_CORE_JAR=$(findJar commons-jci-core)
COMMONS_JCI_FAM_JAR=$(findJar commons-jci-fam)
GUAVA_JAR=$(findJar guava)
PROTOBUF_JAR=$(findJar protobuf-java)
JODA_TIME_JAR=$(findJar joda-time)
SLF4J_JAR=$(findJar slf4j-api)
SLF4J_BRIDGE_JAR=$(findJar slf4j-log4j12)
LOG4J_JAR=$(findJar log4j)
LOG4J_EXTRAS_JAR=$(findJar apache-log4j-extras)
LUCENE_JAR=$(findJar lucene-core)
LUCENE_JAR=$LUCENE_JAR:$(findJar lucene-queryparser)
LUCENE_JAR=$LUCENE_JAR:$(findJar lucene-analyzers-common)
THRIFT_JAR=$(findJar libthrift)
AC_CORE_JAR=$WAREHOUSE_ACCUMULO_LIB/accumulo-core.jar
AC_SERVER_JAR=$WAREHOUSE_ACCUMULO_LIB/accumulo-server-base.jar
AC_FATE_JAR=$WAREHOUSE_ACCUMULO_LIB/accumulo-fate.jar
AC_START_JAR=$WAREHOUSE_ACCUMULO_LIB/accumulo-start.jar
AC_TRACE_JAR=$WAREHOUSE_ACCUMULO_LIB/accumulo-trace.jar
AC_HTRACE_JAR=$(findJar htrace-core)
VFS_JAR=`ls -1 $WAREHOUSE_ACCUMULO_LIB/commons-vfs*.jar | sort | head -1`
ASM_JAR=$(findJar asm)
KRYO_JAR=$(findJar kryo)
MINLOG_JAR=$(findJar minlog)
REFLECT_ASM_JAR=$(findJar reflectasm)
INFINISPAN_CORE_JAR=$(findJar infinispan-core)
INFINISPAN_COMMONS_JAR=$(findJar infinispan-commons)
JBOSS_LOGGING_JAR=$(findJar jboss-logging)
JGROUPS_JAR=$(findJar jgroups)
ZOOKEEPER_JAR=$ZOOKEEPER_HOME/zookeeper-$ZOOKEEPER_VERSION.jar
DATAWAVE_QUERY_CORE_JAR=$(findJar datawave-query-core)
COMMONS_JEXL_JAR=$(findJar commons-jexl)
PROTOSTUFF_API_JAR=$(findJar protostuff-api)
PROTOSTUFF_CORE_JAR=$(findJar protostuff-core)
JAXRS_API_JAR=$(findJar jaxrs-api)
EDGE_KEY_VERSION_CACHE_FILE=${CONF_DIR}/edge-key-version.txt
OPENCSV_JAR=$(findJar opencsv)
SPRING_CORE_JAR=$(findJar spring-core)
SPRING_CONTEXT_JAR=$(findJar spring-context)
SPRING_CONTEXT_SUPPORT_JAR=$(findJar spring-context-support)
SPRING_BEAN_JAR=$(findJar spring-beans)
SPRING_AOP_JAR=$(findJar spring-aop)
SPRING_EXPRESSION_JAR=$(findJar spring-expression)
COMMON_JAR=$(findJar datawave-ws-common)
#XERCES_JAR=$(findJar org.apache.xerces)
JCOMMANDER_JAR=$(findJar jcommander)

#for geo hilbert curve processing
JTS_JAR=$(findJar jts)
GEOWAVE_ADAPTER_RASTER_JAR=$(findJar geowave-adapter-raster)
GEOWAVE_ADAPTER_VECTOR_JAR=$(findJar geowave-adapter-vector)
GEOWAVE_CORE_CLI_JAR=$(findJar geowave-core-cli)
GEOWAVE_CORE_INDEX_JAR=$(findJar geowave-core-index)
GEOWAVE_CORE_INGEST_JAR=$(findJar geowave-core-ingest)
GEOWAVE_CORE_MAPREDUCE_JAR=$(findJar geowave-core-mapreduce)
GEOWAVE_CORE_STORE_JAR=$(findJar geowave-core-store)
GEOWAVE_CORE_GEOTIME_JAR=$(findJar geowave-core-geotime)
GEOWAVE_DATASTORE_ACCUMULO_JAR=$(findJar geowave-datastore-accumulo)
UZAYGEZEN_JAR=$(findJar uzaygezen-core)
VECMATH_JAR=$(findJar vecmath)
GT_OPENGIS_JAR=$(findJar gt-opengis)
GT_API_JAR=$(findJar gt-api)
GT_DATA_JAR=$(findJar gt-data)
GT_EPSG_JAR=$(findJar gt-epsg-wkt)
GT_MAIN_JAR=$(findJar gt-main)
GT_MD_JAR=$(findJar gt-metadata)
GT_REF_JAR=$(findJar gt-referencing)
GT_SHAPE_JAR=$(findJar gt-shapefile)

JAXB_IMPL_JAR=$(findJar resteasy-jaxb-provider)

# extra jars
COMMONS_CLI_JAR=$(findJar commons-cli)
COMMONS_LOGGING_JAR=$(findJar commons-logging)
COMMONS_CODEC_JAR=$(findJar commons-codec)
HORNETQ_CLIENT_JAR=$(findJar hornetq-core-client)
HORNETQ_COMMONS_JAR=$(findJar hornetq-commons)
HORNETQ_NETTY_JAR=$(findJar netty-all)

# These aren't on the classpath by default, but here for reference in
# other scripts.
DATAWAVE_CACHE_JAR=../../lib/datawave-cache-client-$INGEST_VERSION.jar
DATAWAVE_CACHE_THRIFT_JAR=../../lib/datawave-cache-client-thrift-0.9-$INGEST_VERSION.jar

#
# Jars
#
CLASSPATH=${CONF_DIR}
CLASSPATH=${CLASSPATH}:${COMMON_UTIL_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_JEXL_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_CORE_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_BALANCERS_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_QUERY_CORE_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_COMMON_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_INGEST_CORE_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_INGEST_CONFIG_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_INGEST_CSV_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_INGEST_JSON_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_INGEST_WIKIPEDIA_JAR}
CLASSPATH=${CLASSPATH}:${CURATOR_FRAMEWORK_JAR}
CLASSPATH=${CLASSPATH}:${CURATOR_UTILS_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_LANG_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_LANG3_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_CONFIGURATION_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_COLLECTIONS_JAR}
CLASSPATH=${CLASSPATH}:${AVRO_JAR}
CLASSPATH=${CLASSPATH}:${HTTPCORE_JAR}
CLASSPATH=${CLASSPATH}:${HTTPCLIENT_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_IO_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_POOL_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_JCI_CORE_JAR}
CLASSPATH=${CLASSPATH}:${COMMONS_JCI_FAM_JAR}
CLASSPATH=${CLASSPATH}:${GUAVA_JAR}
CLASSPATH=${CLASSPATH}:${PROTOBUF_JAR}
CLASSPATH=${CLASSPATH}:${JODA_TIME_JAR}
CLASSPATH=${CLASSPATH}:${SLF4J_JAR}
CLASSPATH=${CLASSPATH}:${LOG4J_JAR}
CLASSPATH=${CLASSPATH}:${LOG4J_EXTRAS_JAR}
CLASSPATH=${CLASSPATH}:${LUCENE_JAR}
CLASSPATH=${CLASSPATH}:${THRIFT_JAR}
CLASSPATH=${CLASSPATH}:${AC_CORE_JAR}
CLASSPATH=${CLASSPATH}:${AC_SERVER_JAR}
CLASSPATH=${CLASSPATH}:${AC_FATE_JAR}
CLASSPATH=${CLASSPATH}:${AC_START_JAR}
CLASSPATH=${CLASSPATH}:${AC_TRACE_JAR}
CLASSPATH=${CLASSPATH}:${AC_HTRACE_JAR}
CLASSPATH=${CLASSPATH}:${VFS_JAR}
CLASSPATH=${CLASSPATH}:${ASM_JAR}
CLASSPATH=${CLASSPATH}:${KRYO_JAR}
CLASSPATH=${CLASSPATH}:${MINLOG_JAR}
CLASSPATH=${CLASSPATH}:${REFLECT_ASM_JAR}
CLASSPATH=${CLASSPATH}:${INFINISPAN_CORE_JAR}
CLASSPATH=${CLASSPATH}:${INFINISPAN_COMMONS_JAR}
CLASSPATH=${CLASSPATH}:${JBOSS_LOGGING_JAR}
CLASSPATH=${CLASSPATH}:${JGROUPS_JAR}
CLASSPATH=${CLASSPATH}:${ZOOKEEPER_JAR}
CLASSPATH=${CLASSPATH}:${OPENCSV_JAR}
CLASSPATH=${CLASSPATH}:${STREAMLIB}
CLASSPATH=${CLASSPATH}:${JCOMMANDER_JAR}

#for geo hilbert curve processing
CLASSPATH=${CLASSPATH}:${VECMATH_JAR}
CLASSPATH=${CLASSPATH}:${JTS_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_ADAPTER_RASTER_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_ADAPTER_VECTOR_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_CORE_CLI_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_CORE_INGEST_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_CORE_INDEX_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_CORE_MAPREDUCE_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_CORE_STORE_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_CORE_GEOTIME_JAR}
CLASSPATH=${CLASSPATH}:${GEOWAVE_DATASTORE_ACCUMULO_JAR}
CLASSPATH=${CLASSPATH}:${UZAYGEZEN_JAR}
CLASSPATH=${CLASSPATH}:${GT_OPENGIS_JAR}
CLASSPATH=${CLASSPATH}:${GT_API_JAR}
CLASSPATH=${CLASSPATH}:${GT_DATA_JAR}
CLASSPATH=${CLASSPATH}:${GT_EPSG_JAR}
CLASSPATH=${CLASSPATH}:${GT_MAIN_JAR}
CLASSPATH=${CLASSPATH}:${GT_MD_JAR}
CLASSPATH=${CLASSPATH}:${GT_REF_JAR}
CLASSPATH=${CLASSPATH}:${GT_SHAPE_JAR}

CLASSPATH=${CLASSPATH}:${JAXB_IMPL_JAR}

#for json
CLASSPATH=${CLASSPATH}:$(findJar json-simple)

#for query
CLASSPATH=${CLASSPATH}:${DATAWAVE_WS_QUERY_JAR}
CLASSPATH=${CLASSPATH}:${DATAWAVE_WS_CLIENT_JAR}
CLASSPATH=${CLASSPATH}:${PROTOSTUFF_CORE_JAR}
CLASSPATH=${CLASSPATH}:${PROTOSTUFF_API_JAR}
CLASSPATH=${CLASSPATH}:${COMMON_JAR}

#required for edge ingest
CLASSPATH=${CLASSPATH}:${EDGE_KEY_VERSION_CACHE_FILE}

CLASSPATH=${CLASSPATH}:${SPRING_CORE_JAR}
CLASSPATH=${CLASSPATH}:${SPRING_CONTEXT_JAR}
CLASSPATH=${CLASSPATH}:${SPRING_CONTEXT_SUPPORT_JAR}
CLASSPATH=${CLASSPATH}:${SPRING_BEAN_JAR}
CLASSPATH=${CLASSPATH}:${SPRING_AOP_JAR}
CLASSPATH=${CLASSPATH}:${SPRING_EXPRESSION_JAR}

if [[ "$ADDITIONAL_INGEST_LIBS" != "" ]]; then
    CLASSPATH=${CLASSPATH}:$ADDITIONAL_INGEST_LIBS
fi


export HADOOP_USER_CLASSPATH_FIRST=true
