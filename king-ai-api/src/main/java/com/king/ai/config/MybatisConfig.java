package com.king.ai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.king.ai.common.security.RequestContext;
import com.king.ai.common.security.UserPrincipal;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class MybatisConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(KingAiProperties properties) {
        DbType dbType = "h2".equalsIgnoreCase(properties.getDbType()) ? DbType.H2 : DbType.MYSQL;
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(dbType);
        pagination.setMaxLimit(200L);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
                Long actorId = currentActorId();
                if (actorId != null) {
                    strictInsertFill(metaObject, "createdBy", Long.class, actorId);
                    strictInsertFill(metaObject, "updatedBy", Long.class, actorId);
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                Long actorId = currentActorId();
                if (actorId != null) {
                    strictUpdateFill(metaObject, "updatedBy", Long.class, actorId);
                }
            }
        };
    }

    private static Long currentActorId() {
        UserPrincipal principal = RequestContext.get();
        return principal == null ? null : principal.id();
    }
}
