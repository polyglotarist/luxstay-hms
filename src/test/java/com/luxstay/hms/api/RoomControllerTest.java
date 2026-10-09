package com.luxstay.hms.api;

import com.luxstay.hms.config.SecurityConfig;
import com.luxstay.hms.controller.RoomController;
import com.luxstay.hms.controller.RoomTypeController;
import com.luxstay.hms.dto.response.RoomResponse;
import com.luxstay.hms.dto.response.RoomTypeResponse;
import com.luxstay.hms.enums.RoomStatus;
import com.luxstay.hms.exception.BusinessRuleException;
import com.luxstay.hms.exception.ResourceNotFoundException;
import com.luxstay.hms.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RoomController.class, RoomTypeController.class})
@Import(SecurityConfig.class)
class RoomControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean RoomService roomService;

    @Test
    void create_withValidBody_returns201WithLocation() throws Exception {
        when(roomService.create(any())).thenReturn(
                new RoomResponse(1L, "613", 6, "SEA", RoomStatus.VACANT_CLEAN, "DLX", "Deluxe King"));

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"number":"613","floor":6,"viewType":"SEA","roomTypeId":1}
                    """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/rooms/1"))
                .andExpect(jsonPath("$.status").value("VACANT_CLEAN"));
    }

    @Test
    void create_withBlankNumber_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"number":"","floor":6,"roomTypeId":1}
                    """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.number").exists());
    }

    @Test
    void create_whenDuplicate_returns409() throws Exception {
        when(roomService.create(any())).thenThrow(new BusinessRuleException("Room 613 already exists"));

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"number":"613","floor":6,"roomTypeId":1}
                    """))
                .andExpect(status().isConflict());
    }

    @Test
    void getById_whenMissing_returns404() throws Exception {
        when(roomService.getById(99L)).thenThrow(new ResourceNotFoundException("Room", 99L));

        mockMvc.perform(get("/api/v1/rooms/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAll_withStatus_returnsPageOfRooms() throws Exception {
        when(roomService.getAll(eq(RoomStatus.VACANT_CLEAN), any(Pageable.class))).thenReturn(
                new PageImpl<>(List.of(
                        new RoomResponse(1L, "101", 1, "CITY", RoomStatus.VACANT_CLEAN, "DLX", "Deluxe King"))));

        mockMvc.perform(get("/api/v1/rooms").param("status", "VACANT_CLEAN").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].number").value("101"));
    }

    @Test
    void updateStatus_withValidStatus_returns200() throws Exception {
        when(roomService.updateStatus(1L, RoomStatus.OUT_OF_ORDER)).thenReturn(
                new RoomResponse(1L, "101", 1, "CITY", RoomStatus.OUT_OF_ORDER, "DLX", "Deluxe King"));

        mockMvc.perform(patch("/api/v1/rooms/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"status":"OUT_OF_ORDER"}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_ORDER"));
    }

    @Test
    void updateStatus_withUnknownStatus_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/rooms/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"status":"SPARKLING"}
                    """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRoomTypes_returns200() throws Exception {
        when(roomService.getRoomTypes()).thenReturn(List.of(
                new RoomTypeResponse(1L, "DLX", "Deluxe King", 2, new BigDecimal("450.00"), null)));

        mockMvc.perform(get("/api/v1/room-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("DLX"));
    }
}