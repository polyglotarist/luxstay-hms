package com.luxstay.hms.service;

import com.luxstay.hms.dto.request.CreateRoomRequest;
import com.luxstay.hms.dto.response.RoomResponse;
import com.luxstay.hms.dto.response.RoomTypeResponse;
import com.luxstay.hms.enums.RoomStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RoomService {
    RoomResponse create(CreateRoomRequest request);
    RoomResponse getById(Long id);
    Page<RoomResponse> getAll(RoomStatus status, Pageable pageable);
    RoomResponse updateStatus(Long id, RoomStatus status);
    List<RoomTypeResponse> getRoomTypes();
}