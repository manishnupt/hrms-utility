package com.hrms.hrms_utility.response;

import java.time.LocalDate;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
public class LeaveDto  {

    private Long leaveId;
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    private int totalDays;
    private String reason;
}
