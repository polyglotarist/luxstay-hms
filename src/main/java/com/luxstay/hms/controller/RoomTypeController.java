package com.luxstay.hms.controller;


import com.luxstay.hms.dto.response.RoomTypeResponse;
import com.luxstay.hms.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/room-types")
@RequiredArgsConstructor
@Tag(name = "Rooms")
public class RoomTypeController {

    private final RoomService roomService;

    @GetMapping
    @Operation(summary = "List room types")
    public List<RoomTypeResponse> getAll() {
        return roomService.getRoomTypes();
    }
}