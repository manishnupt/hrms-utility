package com.hrms.hrms_utility.request;

import com.hrms.hrms_utility.response.LeaveDto;
import com.hrms.hrms_utility.response.TimesheetDto;
import com.hrms.hrms_utility.response.WorkFromHomeDto;
import lombok.Data;

@Data
public class ExternalServiceResponse {

    private LeaveDto leave;
    private TimesheetDto timesheet;
    private WorkFromHomeDto wfh;
    // private ExpenseDto expense;
    // private AssetRequestDto assetRequest;

    private String type;
}
