package com.luxstay.hms.mapper;

import com.luxstay.hms.dto.response.RoomTypeResponse;
import com.luxstay.hms.entity.RoomType;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RoomTypeMapper {
    RoomTypeResponse toResponse(RoomType roomType);
}