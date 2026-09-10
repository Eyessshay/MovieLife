package com.movielife.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class VerifyTicketDTO {

    @NotBlank(message = "票码不能为空")
    @Size(max = 64, message = "票码长度不能超过64位")
    private String ticketCode;

    public void setTicketCode(String ticketCode) {
        this.ticketCode = ticketCode == null ? null : ticketCode.trim();
    }
}
