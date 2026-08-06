package com.aiimsk.it.repository;

import com.aiimsk.it.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("select e from Event e " +
            "where (:month is null or month(e.eventDate) = :month) " +
            "and (:year is null or year(e.eventDate) = :year) " +
            "order by e.eventDate desc")
    List<Event> searchByMonthAndYear(@Param("month") Integer month,
            @Param("year") Integer year);

    @Query("select count(e) from Event e where year(e.eventDate) = :year and month(e.eventDate) = :month")
    long countByMonthAndYear(@Param("month") int month, @Param("year") int year);

    @Query("select count(e) from Event e where year(e.eventDate) = :year")
    long countByYear(@Param("year") int year);
}