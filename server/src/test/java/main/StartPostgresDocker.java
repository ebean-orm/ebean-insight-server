package main;

import io.ebean.test.containers.PostgresContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Start a local postgres docker container.
 * <p>
 * Creates the database and user which we can then use to
 * run locally via Application.main().
 */
public class StartPostgresDocker {

  public static void main(String[] args) {

    PostgresContainer container = PostgresContainer.builder("17")
      .containerName("eb_insight")
      .port(7432)
      .dbName("ebean_insight")
      .user("ebean_insight")
      .password("ebean_insight")
      .build();

    //    container.startWithDropCreate(); // drop and re-create the docker container
    container.start(); // just start the docker container

    System.out.println("url:" + container.jdbcUrl());
    System.out.println("now:" + Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli());
  }
}
