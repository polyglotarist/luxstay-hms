package com.luxstay.hms.unit;

import com.luxstay.hms.dto.request.CreateRoomRequest;
import com.luxstay.hms.dto.response.RoomResponse;
import com.luxstay.hms.entity.Room;
import com.luxstay.hms.entity.RoomType;
import com.luxstay.hms.enums.RoomStatus;
import com.luxstay.hms.exception.BusinessRuleException;
import com.luxstay.hms.exception.ResourceNotFoundException;
import com.luxstay.hms.mapper.RoomMapper;
import com.luxstay.hms.mapper.RoomTypeMapper;
import com.luxstay.hms.repository.RoomRepository;
import com.luxstay.hms.repository.RoomTypeRepository;
import com.luxstay.hms.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceImplTest {

    @Mock RoomRepository roomRepository;
    @Mock RoomTypeRepository roomTypeRepository;
    @Spy RoomMapper roomMapper = Mappers.getMapper(RoomMapper.class);
    @Spy RoomTypeMapper roomTypeMapper = Mappers.getMapper(RoomTypeMapper.class);
    @InjectMocks RoomServiceImpl roomService;

    @Test
    void create_whenNumberIsNew_savesVacantCleanRoom() {
        RoomType deluxe = new RoomType();
        deluxe.setId(1L);
        deluxe.setCode("DLX");
        deluxe.setName("Deluxe King");
        when(roomRepository.existsByNumber("613")).thenReturn(false);
        when(roomTypeRepository.findById(1L)).thenReturn(Optional.of(deluxe));
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        RoomResponse response = roomService.create(new CreateRoomRequest("613", 6, "SEA", 1L));

        assertThat(response.status()).isEqualTo(RoomStatus.VACANT_CLEAN);
        assertThat(response.roomTypeCode()).isEqualTo("DLX");
    }

    @Test
    void create_whenNumberExists_throwsBusinessRuleException() {
        when(roomRepository.existsByNumber("613")).thenReturn(true);

        assertThatThrownBy(() -> roomService.create(new CreateRoomRequest("613", 6, "SEA", 1L)))
                .isInstanceOf(BusinessRuleException.class);
        verify(roomRepository, never()).save(any());
    }

    @Test
    void create_whenRoomTypeMissing_throwsNotFound() {
        when(roomRepository.existsByNumber("613")).thenReturn(false);
        when(roomTypeRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.create(new CreateRoomRequest("613", 6, "SEA", 9L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStatus_changesStatus() {
        Room room = new Room();
        room.setNumber("101");
        room.setStatus(RoomStatus.VACANT_CLEAN);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));

        RoomResponse response = roomService.updateStatus(1L, RoomStatus.OUT_OF_ORDER);

        assertThat(response.status()).isEqualTo(RoomStatus.OUT_OF_ORDER);
    }

    @Test
    void getAll_withStatus_usesStatusQuery() {
        when(roomRepository.findByStatus(eq(RoomStatus.OCCUPIED), any(Pageable.class)))
                .thenReturn(Page.empty());

        roomService.getAll(RoomStatus.OCCUPIED, PageRequest.of(0, 10));

        verify(roomRepository).findByStatus(eq(RoomStatus.OCCUPIED), any(Pageable.class));
    }

    @Test
    void getById_whenMissing_throwsNotFound() {
        when(roomRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}