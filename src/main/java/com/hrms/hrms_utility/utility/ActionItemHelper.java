package com.hrms.hrms_utility.utility;

import com.hrms.hrms_utility.entity.ActionItem;
import com.hrms.hrms_utility.request.ActionItemRequest;

public class ActionItemHelper {
    public static ActionItem toEntity(ActionItemRequest request) {
        return ActionItem.builder()
                    .title(request.getTitle())
                    .description(request.getDescription())
                    .type(ActionItem.ActionType.valueOf(request.getType().name()))
                    .status(ActionItem.ActionStatus.PENDING) // default to PENDING
                    .initiatorUserId(request.getInitiatorUserId())
                    .assigneeUserId(request.getAssigneeUserId())
                    .referenceId(request.getReferenceId())
                    .build();
    }

}
