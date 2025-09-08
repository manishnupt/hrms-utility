package com.hrms.hrms_utility.response;


import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;


@Data
@Builder
@EqualsAndHashCode(callSuper = true) 
public class ActionItemResponse extends ApiResponse{

    private Long id;
    private String title;
    private String description;
    private String remarks;
    private boolean seen;
    private String type;
    private String status;
    private EmployeeDto initiatorUser;
    private EmployeeDto assigneeUser;
    private BaseDto reference;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum ActionType {
        TIMESHEET, LEAVE, WFH, EXPENSE, ASSET_REQUEST
    }

    public enum ActionStatus {
        PENDING, APPROVED, REJECTED
    }
}
