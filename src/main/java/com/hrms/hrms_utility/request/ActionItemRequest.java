package com.hrms.hrms_utility.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionItemRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    @NotNull(message = "Type is required")
    private ActionType type;

    @NotNull(message = "Initiator user ID is required")
    private String initiatorUserId;

    @NotNull(message = "Assignee user ID is required")
    private String assigneeUserId;

    private Long referenceId;

    public enum ActionType {
        TIMESHEET, LEAVE, WFH, EXPENSE, ASSET_REQUEST
    }
}
