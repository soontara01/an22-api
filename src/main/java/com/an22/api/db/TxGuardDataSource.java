package com.an22.api.db;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.core.InfrastructureProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * DataSource ที่ JdbcClient ใช้: ขอ connection ได้เฉพาะใน transaction — query นอก transaction → error ทันที (กันลืม
 * {@code @Transactional}: ผ่าน PgBouncer มันจะได้ connection ที่ไม่มีบริษัท RLS ให้ 0 แถว = บั๊กเงียบ).
 *
 * <p>เป็น {@link InfrastructureProxy} ของ DataSource จริง → {@code DataSourceUtils} หา connection ของ transaction ที่ผูกกับตัวจริงเจอ
 * (ใน transaction ไม่เรียก {@link #getConnection()} ของ class นี้เลย).
 */
public class TxGuardDataSource extends DelegatingDataSource implements InfrastructureProxy {

  public TxGuardDataSource(DataSource target) {
    super(target);
  }

  @Override
  public Connection getConnection() {
    throw new IllegalStateException("query นอก transaction — ใส่ @Transactional ที่ service");
  }

  @Override
  public Connection getConnection(String username, String password) {
    throw new IllegalStateException("query นอก transaction — ใส่ @Transactional ที่ service");
  }

  @Override
  public Object getWrappedObject() {
    return obtainTargetDataSource();
  }
}
