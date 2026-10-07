package com.luxstay.hms.controller;

import com.luxstay.hms.dto.request.CreateRoomRequest;
import com.luxstay.hms.dto.request.UpdateRoomStatusRequest;
import com.luxstay.hms.dto.response.RoomResponse;
import com.luxstay.hms.enums.RoomStatus;
import com.luxstay.hms.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@Tag(name = "Rooms", description = "Room inventory and status")
public class RoomController {

    private final RoomService roomService;

    @PostMapping
    @Operation(summary = "Create a room")
    @ApiResponse(responseCode = "201", description = "Room created")
    @ApiResponse(responseCode = "409", description = "Room number already exists")
    public ResponseEntity<RoomResponse> create(@Valid @RequestBody CreateRoomRequest request) {
        RoomResponse created = roomService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a room by id")
    public RoomResponse getById(@PathVariable Long id) {
        return roomService.getById(id);
    }

    @GetMapping
    @Operation(summary = "List rooms, optionally filtered by status")
    public Page<RoomResponse> getAll(@RequestParam(required = false) RoomStatus status,
                                     @ParameterObject Pageable pageable) {
        return roomService.getAll(status, pageable);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change a room's status")
    public RoomResponse updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody UpdateRoomStatusRequest request) {
        return roomService.updateStatus(id, request.status());
    }
}