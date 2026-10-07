package com.luxstay.hms.mapper;

import com.luxstay.hms.dto.response.RoomResponse;
import com.luxstay.hms.entity.Room;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RoomMapper {

    @Mapping(target = "roomTypeCode", source = "roomType.code")
    @Mapping(target = "roomTypeName", source = "roomType.name")
    RoomResponse toResponse(Room room);
}