# syntax=docker/dockerfile:1.2

# manage base versions
ARG ALPINE_VERSION="3.15"
ARG MAVEN_VERSION="3.8.4"
ARG OPENJDK_VERSION="17"

# configure some paths, names and args
ARG JAVA_MINIMAL="/opt/java-minimal"


##################################
# Build the Spatial Indexer tool #
##################################
FROM --platform=${BUILDPLATFORM} "docker.io/library/maven:${MAVEN_VERSION}-openjdk-${OPENJDK_VERSION}-slim" AS builder

WORKDIR /build
COPY ./src ./src
COPY ./pom.xml ./pom.xml
RUN --mount=type=cache,target=/root/.m2,rw mvn clean compile package \
  && mv target/spatialindexer-*-jar-with-dependencies.jar spatialindexer.jar \
  && mvn clean

# figure out JDEPS
RUN jdeps \
  --multi-release base \
  --print-module-deps \
  --ignore-missing-deps \
  spatialindexer.jar \
  > /tmp/jdeps


#############################################################
# Generate all depedencies depending on the target platform #
#############################################################
FROM --platform=${TARGETPLATFORM} "docker.io/library/alpine:${ALPINE_VERSION}" as deps
ARG OPENJDK_VERSION
ARG JAVA_MINIMAL

WORKDIR /app
RUN apk add --no-cache "openjdk${OPENJDK_VERSION}"
COPY --from=builder /tmp/jdeps /tmp/jdeps
RUN \
  jlink \
  --compress 2 --no-header-files --no-man-pages \
  --output "${JAVA_MINIMAL}" \
  --add-modules "$(cat /tmp/jdeps),java.naming"


############################
# Build final Docker image #
############################
FROM --platform=${TARGETPLATFORM} "docker.io/library/alpine:${ALPINE_VERSION}"
ARG JAVA_MINIMAL

# Run as this user
# -H: no home directorry
# -D: no password
# -u: explicit UID
RUN adduser -H -D -u 1000 spatialindex spatialindex

WORKDIR /app

COPY --from=deps "${JAVA_MINIMAL}" "${JAVA_MINIMAL}"
COPY --from=builder /build/spatialindexer.jar .
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh

# default environment variables
ENV \
  JAVA_HOME="${JAVA_MINIMAL}" \
  JAVA_OPTIONS="" \
  SRS_URI="" \
  DATASET_PATH="/databases/ds" \
  SPATIAL_INDEX_FILE_PATH="/databases/ds/spatial.index"

RUN mkdir -p "/databases/ds" && chown -R 1000:1000 /databases
USER 1000

ENTRYPOINT [ "/app/entrypoint.sh" ]
CMD []
