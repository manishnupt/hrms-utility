package com.hrms.hrms_utility.response;


import java.time.LocalDateTime;

import jakarta.persistence.Column;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ActionItemResponse {

    private Long id;
    private String title;
    private String description;
    private String remarks;
    private boolean seen;
    private String type;
    private String status;
    private Object initiatorUser;
    private Object assigneeUser;
    private Object reference;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum ActionType {
        TIMESHEET, LEAVE, WFH, EXPENSE, ASSET_REQUEST
    }

    public enum ActionStatus {
        PENDING, APPROVED, REJECTED
    }
}
