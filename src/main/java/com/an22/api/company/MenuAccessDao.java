package com.an22.api.company;

import com.an22.api.model.MenuAccess;
import com.an22.api.rules.MenuAccessRules;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** dt_menu_access ของบริษัทใน transaction — ไม่มีแถว = ค่าเริ่ม ({@code DEFAULT_MENU_ACCESS}). */
@Repository
public class MenuAccessDao {

  private final JdbcClient jdbc;

  MenuAccessDao(JdbcClient appJdbc) {
    this.jdbc = appJdbc;
  }

  public MenuAccess get() {
    return jdbc.sql("SELECT user_menus FROM dt_menu_access")
        .query((rs, i) -> new MenuAccess(List.of((String[]) rs.getArray("user_menus").getArray())))
        .optional()
        .orElse(new MenuAccess(MenuAccessRules.DEFAULT_USER_MENUS));
  }

  public void save(List<String> userMenus) {
    jdbc.sql(
            "INSERT INTO dt_menu_access (tenant_id, user_menus) VALUES (app_tenant_id(), :menus)"
                + " ON CONFLICT (tenant_id) DO UPDATE SET user_menus = EXCLUDED.user_menus")
        .param("menus", userMenus.toArray(String[]::new))
        .update();
  }
}
