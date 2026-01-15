package com.hrms.hrms_utility.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
public class WorkFromHomeDto {

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    private String wfhCreditOption;
    private String status;
    private String reason;

    private LocalDate createdDate;
    private LocalDate updatedDate;
}
