package com.an22.api.health;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HealthDao {

  private final JdbcClient jdbc;

  HealthDao(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void ping() {
    jdbc.sql("SELECT 1").query(Integer.class).single();
  }
}
