   INSERT INTO room_type (code, name, max_occupancy, base_rate, description) VALUES
       ('DLX', 'Deluxe King',        2,  450.00, 'King bed, marble bathroom, city or sea view'),
       ('JRS', 'Junior Suite',       3,  750.00, 'Separate lounge area and soaking tub'),
       ('PRS', 'Presidential Suite', 4, 3500.00, 'Two bedrooms, private terrace, butler service');

   INSERT INTO room (number, floor, view_type, status, room_type_id)
   SELECT (f * 100 + n)::text,
          f,
          CASE WHEN n % 2 = 0 THEN 'SEA' ELSE 'CITY' END,
          'VACANT_CLEAN',
          (SELECT id FROM room_type
            WHERE code = CASE WHEN n <= 8 THEN 'DLX' WHEN n <= 11 THEN 'JRS' ELSE 'PRS' END)
   FROM generate_series(1, 5) AS f, generate_series(1, 12) AS n;