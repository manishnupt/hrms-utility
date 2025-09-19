package com.hrms.hrms_utility.utility;

import com.hrms.hrms_utility.entity.ActionItem;
import com.hrms.hrms_utility.request.ActionItemRequest;
import com.hrms.hrms_utility.response.ActionItemResponse;

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

    public static ActionItemResponse convertToResponse(ActionItem item) {
        return ActionItemResponse.builder()
                .id(item.getId())
                .title(item.getTitle())
                .description(item.getDescription())
                .remarks(item.getRemarks())
                .seen(item.isSeen())
                .type(item.getType().name())
                .status(item.getStatus().name())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
