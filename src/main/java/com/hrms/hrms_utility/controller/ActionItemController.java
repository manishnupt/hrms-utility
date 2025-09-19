package com.hrms.hrms_utility.controller;

import com.hrms.hrms_utility.entity.ActionItem;
import com.hrms.hrms_utility.request.ActionItemRequest;
import com.hrms.hrms_utility.response.ActionItemResponse;
import com.hrms.hrms_utility.response.ApiResponse;
import com.hrms.hrms_utility.service.ActionItemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/action-item")
@CrossOrigin(origins ="*")
public class ActionItemController {

    @Autowired
    private ActionItemService actionItemService;


    @PostMapping
    public ResponseEntity<ActionItem> createActionItem(@RequestBody ActionItemRequest req) {
        ActionItem actionItem = actionItemService.createActionItem(req);
        return ResponseEntity.ok(actionItem);
    }

    @GetMapping("/assignee/{userId}")
    public ResponseEntity<List<ActionItemResponse>> getAssignedItems(
            @PathVariable String userId,
            @RequestParam(required = false) ActionItem.ActionStatus status
    ) {
        return ResponseEntity.ok(actionItemService.getAssignedItems(userId, status));
    }

    // 3. Get action items initiated by employee
    @GetMapping("/initiator/{userId}")
    public ResponseEntity<List<ActionItemResponse>> getInitiatedItems(
            @PathVariable String userId,
            @RequestParam(required = false) ActionItem.ActionStatus status
    ) {
        return ResponseEntity.ok(actionItemService.getInitiatedItems(userId, status));
    }

    // 4. Get a single action item
    @GetMapping("/{id}")
    public ResponseEntity<ActionItem> getActionItem(@PathVariable Long id) {
        return ResponseEntity.ok(actionItemService.getActionItemById(id));
    }

    // 5. Approve or Reject an action item
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse> updateActionItemStatus(
            @PathVariable Long id,
            @RequestParam ActionItem.ActionStatus status,
            @RequestParam(required = false) String remarks
    ) {
        return ResponseEntity.ok(actionItemService.updateStatus(id, status, remarks));
    }

    @PutMapping("/{id}/mark-seen")
    public ResponseEntity<Void> markAsSeen(@PathVariable Long id) {
        actionItemService.markAsSeen(id);
        return ResponseEntity.ok().build();
    }

    // 7. Delete action item (optional/admin)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteActionItem(@PathVariable Long id) {
        actionItemService.deleteActionItem(id);
        return ResponseEntity.noContent().build();
    }

    // 8. Get pending count for badge
    @GetMapping("/assignee/{userId}/count")
    public ResponseEntity<Long> getPendingCount(@PathVariable String userId) {
        return ResponseEntity.ok(actionItemService.getPendingCount(userId));
    }

    @GetMapping("/reference/{referenceId}")
    public ResponseEntity<List<ActionItem>> getByReferenceId(@PathVariable Long referenceId) {
        return ResponseEntity.ok(actionItemService.getByReferenceId(referenceId));
    }

    // 10. Get action items by reference ID and type
    @GetMapping("/reference/{referenceId}/type")
    public ResponseEntity<List<ActionItem>> getByReferenceIdAndType(
            @PathVariable Long referenceId,
            @RequestParam ActionItem.ActionType type
    ) {
        return ResponseEntity.ok(actionItemService.getByReferenceIdAndType(referenceId, type));
    }

    // 11. Get action items by reference ID and status
    @GetMapping("/reference/{referenceId}/status")
    public ResponseEntity<List<ActionItem>> getByReferenceIdAndStatus(
            @PathVariable Long referenceId,
            @RequestParam ActionItem.ActionStatus status
    ) {
        return ResponseEntity.ok(actionItemService.getByReferenceIdAndStatus(referenceId, status));
    }
}
