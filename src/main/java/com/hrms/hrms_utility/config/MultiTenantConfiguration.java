package com.hrms.hrms_utility.config;


import com.hrms.hrms_utility.request.TenantDbConfig;
import com.hrms.hrms_utility.request.TenantDbConfigResponse;
import com.hrms.hrms_utility.utility.MultitenantDataSource;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@Log4j2
public class MultiTenantConfiguration {

    @Value("${defaultTenant}")
    private String defaultTenant;

    @Value("${tenant.config.api.url}")
    private String tenantConfigApiUrl;

    @Bean
    @ConfigurationProperties(prefix = "tenants")
    public DataSource dataSource() {
        List<TenantDbConfig> tenantConfigs = fetchTenantConfigsFromApi();
        Map<Object, Object> resolvedDataSources = new HashMap<>();

        for (TenantDbConfig config : tenantConfigs) {
            DataSourceBuilder<?> dataSourceBuilder = DataSourceBuilder.create();
            dataSourceBuilder.driverClassName("org.postgresql.Driver"); // You can move this to config if needed
            dataSourceBuilder.url(config.getDbUrl());
            dataSourceBuilder.username(config.getUsername());
            dataSourceBuilder.password(config.getPassword());

            resolvedDataSources.put(config.getTenantId(), dataSourceBuilder.build());
        }

        AbstractRoutingDataSource dataSource = new MultitenantDataSource();
        dataSource.setDefaultTargetDataSource(resolvedDataSources.get(defaultTenant));
        dataSource.setTargetDataSources(resolvedDataSources);
        dataSource.afterPropertiesSet();

        return dataSource;
    }

    private List<TenantDbConfig> fetchTenantConfigsFromApi() {
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<TenantDbConfigResponse> response =null;
        try {
            response = restTemplate.getForEntity(
                    tenantConfigApiUrl,
                    TenantDbConfigResponse.class
            );
        }catch(Exception ex){
            log.info(ex);
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody().getData();
        } else {
            throw new RuntimeException("Failed to fetch tenant configs from API");
        }
    }

}
