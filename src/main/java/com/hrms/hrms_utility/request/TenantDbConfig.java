package com.hrms.hrms_utility.request;

import lombok.Data;

@Data
public class TenantDbConfig {
    private String tenantId;
    private String dbUrl;
    private String username;
    private String password;
}

