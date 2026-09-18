package com.hrms.hrms_utility.controller;

import com.hrms.hrms_utility.entity.ActionItem;
import com.hrms.hrms_utility.request.ActionItemRequest;
import com.hrms.hrms_utility.response.ActionItemResponse;
import com.hrms.hrms_utility.service.ActionItemService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/action-item")
@CrossOrigin(origins ="*")
@Log4j2
public class ActionItemController {

    @Autowired
    private ActionItemService actionItemService;


    @PostMapping
    public ResponseEntity<Long> createActionItem(@RequestBody ActionItemRequest req) {
        log.info("Creating action item: {}", req);
        ActionItem actionItem = actionItemService.createActionItem(req);
        return ResponseEntity.ok(actionItem.getId());
    }

    @GetMapping("/assignee/{userId}")
    public ResponseEntity<List<ActionItemResponse>> getAssignedItems(
            @PathVariable String userId,
            @RequestParam(required = false) ActionItem.ActionStatus status
    ) {
        log.info("Fetching assigned action items for userId: {}, status: {}", userId, status);
        return ResponseEntity.ok(actionItemService.getAssignedItems(userId, status));
    }

    // 3. Get action items initiated by employee
    @GetMapping("/initiator/{userId}")
    public ResponseEntity<List<ActionItemResponse>> getInitiatedItems(
            @PathVariable String userId,
            @RequestParam(required = false) ActionItem.ActionStatus status
    ) {
        log.info("Fetching initiated action items for userId: {}, status: {}", userId, status);
        return ResponseEntity.ok(actionItemService.getInitiatedItems(userId, status));
    }

    // 4. Get a single action item
    @GetMapping("/{id}")
    public ResponseEntity<ActionItem> getActionItem(@PathVariable Long id) {
        log.info("Fetching action item with id: {}", id);
        return ResponseEntity.ok(actionItemService.getActionItemById(id));
    }

    // 5. Approve or Reject an action item
    @PutMapping("/{id}/status")
    public ResponseEntity<ActionItem> updateActionItemStatus(
            @PathVariable Long id,
            @RequestParam ActionItem.ActionStatus status,
            @RequestParam(required = false) String remarks
    ) {
        log.info("Updating action item id: {} to status: {}", id, status);
        return ResponseEntity.ok(actionItemService.updateStatus(id, status, remarks));
    }

    @PutMapping("/{id}/mark-seen")
    public ResponseEntity<Void> markAsSeen(@PathVariable Long id) {
        log.info("Marking action item id: {} as seen", id);
        actionItemService.markAsSeen(id);
        return ResponseEntity.ok().build();
    }

    // 7. Delete action item (optional/admin)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteActionItem(@PathVariable Long id) {
        log.info("Deleting action item with id: {}", id);
        actionItemService.deleteActionItem(id);
        return ResponseEntity.noContent().build();
    }

    // 8. Get pending count for badge
    @GetMapping("/assignee/{userId}/count")
    public ResponseEntity<Long> getPendingCount(@PathVariable String userId) {
        log.info("Fetching pending action item count for userId: {}", userId);
        return ResponseEntity.ok(actionItemService.getPendingCount(userId));
    }

    @GetMapping("/reference/{referenceId}")
    public ResponseEntity<List<ActionItem>> getByReferenceId(@PathVariable Long referenceId) {
        log.info("Fetching action items for referenceId: {}", referenceId);
        return ResponseEntity.ok(actionItemService.getByReferenceId(referenceId));
    }

    // 10. Get action items by reference ID and type
    @GetMapping("/reference/{referenceId}/type")
    public ResponseEntity<List<ActionItem>> getByReferenceIdAndType(
            @PathVariable Long referenceId,
            @RequestParam ActionItem.ActionType type
    ) {
        log.info("Fetching action items for referenceId: {} and type: {}", referenceId, type);
        return ResponseEntity.ok(actionItemService.getByReferenceIdAndType(referenceId, type));
    }

    // 11. Get action items by reference ID and status
    @GetMapping("/reference/{referenceId}/status")
    public ResponseEntity<List<ActionItem>> getByReferenceIdAndStatus(
            @PathVariable Long referenceId,
            @RequestParam ActionItem.ActionStatus status
    ) {
        log.info("Fetching action items for referenceId: {} and status: {}", referenceId, status);
        return ResponseEntity.ok(actionItemService.getByReferenceIdAndStatus(referenceId, status));
    }

}
