package com.hrms.hrms_utility.repository;

import com.hrms.hrms_utility.entity.ActionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Repository
public interface ActionItemRepo extends JpaRepository<ActionItem,Long> {

    List<ActionItem> findByAssigneeUserId(String assigneeUserId);

    List<ActionItem> findByAssigneeUserIdAndStatus(String assigneeUserId, ActionItem.ActionStatus status);

    List<ActionItem> findByInitiatorUserId(String initiatorUserId);

    List<ActionItem> findByInitiatorUserIdAndStatus(String initiatorUserId, ActionItem.ActionStatus status);

    Long countByAssigneeUserIdAndStatus(String assigneeUserId, ActionItem.ActionStatus status);

    List<ActionItem> findByReferenceId(Long referenceId);

    List<ActionItem> findByReferenceIdAndType(Long referenceId, ActionItem.ActionType type);

    List<ActionItem> findByReferenceIdAndStatus(Long referenceId, ActionItem.ActionStatus status);


}
