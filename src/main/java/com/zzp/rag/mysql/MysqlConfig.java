package com.zzp.rag.mysql;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * 业务库装配（软依赖，手工装配不用 spring.datasource 自动配置）。
 * <p>
 * 关键点：
 * 1. DataSource bean 恒存在（enabled=false 时注册 NoopDataSource 占位）——
 *    否则 DataSourceAutoConfiguration 会接管并在无 url 时启动即崩。
 * 2. Hikari initializationFailTimeout=0：MySQL 未起时不炸启动，池懒初始化，
 *    MySQL 恢复后自动重建连接 → 运行时故障自愈。
 * 3. 启动探针只置 {@link MysqlStatus} 状态位，不抛异常。
 * 4. SqlSessionFactory 构建不连库，降级模式下 MyBatis 栈"活着但不可用"，
 *    所有调用在 Service 层 catch 成降级响应。
 */
@Slf4j
@Configuration
@EnableScheduling
@MapperScan("com.zzp.rag.mysql.mapper")
public class MysqlConfig {

    @Bean
    public DataSource dataSource(
            @Value("${rag.mysql.enabled:true}") boolean enabled,
            @Value("${rag.mysql.host:127.0.0.1}") String host,
            @Value("${rag.mysql.port:3306}") int port,
            @Value("${rag.mysql.database:rag_service}") String database,
            @Value("${rag.mysql.username:rag}") String username,
            @Value("${rag.mysql.password:}") String password,
            MysqlStatus status) {
        if (!enabled) {
            log.info("[MySQL] 业务库已禁用（rag.mysql.enabled=false），管理 API 将降级");
            return new NoopDataSource();
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("rag-mysql");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                + "&connectTimeout=10000&socketTimeout=10000");
        config.setUsername(username);
        config.setPassword(password);
        config.setConnectionTimeout(10000);         // 首连常有 3~5s 抖动（MySQL 容器/网络），给足耐心避免启动误降级
        config.setInitializationFailTimeout(0);     // 懒初始化：MySQL 没起不炸启动
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(5);               // 审计聚合 + 管理查询，量级小
        HikariDataSource dataSource = new HikariDataSource(config);

        // 启动探针：只置状态位，不抛异常
        try (Connection conn = dataSource.getConnection()) {
            status.markAvailable();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[MySQL] 探针失败，降级模式启动（问答主链路不受影响）: {}", e.getMessage());
        }
        return dataSource;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.zzp.rag.mysql.entity");
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);   // 分页上限，防大页查询
        interceptor.addInnerInterceptor(pagination);
        factory.setPlugins(interceptor);
        // 手工装配必须显式 afterPropertiesSet（BeanFactoryPostProcessor 生命周期不接管）
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    /**
     * 占位 DataSource：enabled=false 时注册，保证 DataSourceAutoConfiguration 永远 back off。
     * 任何触库调用抛异常 → Service 层 catch 降级。
     */
    static class NoopDataSource implements DataSource {
        private static final SQLException DISABLED =
                new SQLException("MySQL 业务库未启用（rag.mysql.enabled=false）");

        @Override
        public Connection getConnection() throws SQLException {
            throw DISABLED;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw DISABLED;
        }

        @Override
        public PrintWriter getLogWriter() {
            return new PrintWriter(System.out);
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("rag.mysql.noop");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw DISABLED;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
