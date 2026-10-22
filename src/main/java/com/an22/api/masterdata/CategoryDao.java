package com.an22.api.masterdata;

import com.an22.api.model.Category;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_categories ของบริษัทใน transaction — productCount = 0 จนกว่าจะมี products (ขั้น 5). */
@Repository
public class CategoryDao {

  private static final String SELECT = "SELECT id, code, name, parent_id, level, active, 0 AS product_count FROM dt_categories";

  private final JdbcClient jdbc;

  CategoryDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public List<Category> list() {
    return jdbc.sql(SELECT + " ORDER BY id").query(CategoryDao::row).list();
  }

  public Optional<Category> find(long id) {
    return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(CategoryDao::row).optional();
  }

  public long insert(String code, String name, Long parentId, int level, boolean active) {
    return jdbc.sql(
            "INSERT INTO dt_categories (tenant_id, code, name, parent_id, level, active)"
                + " VALUES (app_tenant_id(), :code, :name, :parent, :level, :active) RETURNING id")
        .param("code", code).param("name", name).param("parent", parentId).param("level", level).param("active", active)
        .query(Long.class).single();
  }

  public void update(long id, String code, String name, boolean active) {
    jdbc.sql("UPDATE dt_categories SET code = :code, name = :name, active = :active WHERE id = :id")
        .param("code", code).param("name", name).param("active", active).param("id", id).update();
  }

  public void delete(long id) {
    jdbc.sql("DELETE FROM dt_categories WHERE id = :id").param("id", id).update();
  }

  private static Category row(ResultSet rs, int i) throws SQLException {
    long parent = rs.getLong("parent_id");
    return new Category(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.wasNull() ? null : parent,
        rs.getInt("level"), rs.getBoolean("active"), rs.getInt("product_count"));
  }
}
