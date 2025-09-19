package com.hrms.hrms_utility.response;

import lombok.Data;


@Data
public class EmployeeDto {
    private String name;
    private String username;
    private String role;
    private String email;
    private String phone;
    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
    private String jobTitle;
    private String project;
    private String jobType;
    private String jobStatus;
    private String jobDescription;
    private String password;
    private boolean temporaryPassword;
    private String kcReferenceId;
    private String assignedManagerId;
}
