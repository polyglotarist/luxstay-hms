package com.luxstay.hms.service.impl;

import com.luxstay.hms.dto.request.CreateRoomRequest;
import com.luxstay.hms.dto.response.RoomResponse;
import com.luxstay.hms.dto.response.RoomTypeResponse;
import com.luxstay.hms.entity.Room;
import com.luxstay.hms.entity.RoomType;
import com.luxstay.hms.enums.RoomStatus;
import com.luxstay.hms.exception.BusinessRuleException;
import com.luxstay.hms.exception.ResourceNotFoundException;
import com.luxstay.hms.mapper.RoomMapper;
import com.luxstay.hms.mapper.RoomTypeMapper;
import com.luxstay.hms.repository.RoomRepository;
import com.luxstay.hms.repository.RoomTypeRepository;
import com.luxstay.hms.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomServiceImpl implements RoomService {

    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomMapper roomMapper;
    private final RoomTypeMapper roomTypeMapper;

    @Override
    @Transactional
    public RoomResponse create(CreateRoomRequest request) {
        if (roomRepository.existsByNumber(request.number())) {
            throw new BusinessRuleException("Room " + request.number() + " already exists");
        }
        RoomType type = roomTypeRepository.findById(request.roomTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Room type", request.roomTypeId()));

        Room room = new Room();
        room.setNumber(request.number());
        room.setFloor(request.floor());
        room.setViewType(request.viewType());
        room.setStatus(RoomStatus.VACANT_CLEAN);
        room.setRoomType(type);

        Room saved = roomRepository.save(room);
        log.info("Room {} created", saved.getNumber());
        return roomMapper.toResponse(saved);
    }

    @Override
    public RoomResponse getById(Long id) {
        return roomMapper.toResponse(findRoom(id));
    }

    @Override
    public Page<RoomResponse> getAll(RoomStatus status, Pageable pageable) {
        Page<Room> rooms = (status == null)
                ? roomRepository.findAll(pageable)
                : roomRepository.findByStatus(status, pageable);
        return rooms.map(roomMapper::toResponse);
    }

    @Override
    @Transactional
    public RoomResponse updateStatus(Long id, RoomStatus status) {
        Room room = findRoom(id);
        RoomStatus previous = room.getStatus();
        room.setStatus(status);
        log.info("Room {} status {} -> {}", room.getNumber(), previous, status);
        return roomMapper.toResponse(room);
    }

    @Override
    public List<RoomTypeResponse> getRoomTypes() {
        return roomTypeRepository.findAll().stream().map(roomTypeMapper::toResponse).toList();
    }

    private Room findRoom(Long id) {
        return roomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room", id));
    }
}