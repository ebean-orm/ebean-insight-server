# ebean-insight-server

- Collects metrics from applications using Ebean ORM that send it their metrics.
- Performs a rollup of metrics on 1 min, 10 min, 1 hour basis.
- Supports ability to request Query plans and collect query plans
- For Postgres query plans, uses pev2 to view query plan details

#### Future TODOs:
- Support reporting aggregated metrics onto Graphite, StatsD, etc
- Provide automation for automatically collecting query plans for:
  - new queries,
  - queries that exceed a threshold (anomalies)


## Building local native image
Build on a Mac (no G1GC supported)
```shell
mvn clean package -P native,mac -DskipTests
```
Build on a Linux (with G1GC)
```shell
mvn clean package -P native,linux -DskipTests
```

## Run the application locally

#### Step 1
- Requires docker to be installed locally
- Run the main method on src/test/java/main/StartPostgresDocker

#### Step 2
Run the native application. We pass it an external configuration file via `-Dprops.file=`.
```shell
./target/ebean-insight -Dprops.file=application.yaml
```
