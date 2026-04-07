package io.example.domain;

import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class TimeslotTest {

    private Timeslot emptySlot() {
        return new Timeslot(HashSet.newHashSet(10), HashSet.newHashSet(10));
    }

    @Test
    void reserve_shouldAddParticipantToAvailable() {
        var slot = emptySlot();
        var event = new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT);

        var updated = slot.reserve(event);

        assertTrue(updated.isWaiting("alice", ParticipantType.STUDENT));
        assertEquals(1, updated.available().size());
    }

    @Test
    void unreserve_shouldRemoveParticipantFromAvailable() {
        var slot = emptySlot();
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT));

        var event = new BookingEvent.ParticipantUnmarkedAvailable("slot1", "alice", ParticipantType.STUDENT);
        var updated = slot.unreserve(event);

        assertFalse(updated.isWaiting("alice", ParticipantType.STUDENT));
        assertTrue(updated.available().isEmpty());
    }

    @Test
    void isBookable_shouldReturnTrueWhenAllThreeTypesAvailable() {
        var slot = emptySlot();
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "plane1", ParticipantType.AIRCRAFT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "teacher1", ParticipantType.INSTRUCTOR));

        assertTrue(slot.isBookable("alice", "plane1", "teacher1"));
    }

    @Test
    void isBookable_shouldReturnFalseWhenMissingParticipant() {
        var slot = emptySlot();
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "plane1", ParticipantType.AIRCRAFT));
        // No instructor

        assertFalse(slot.isBookable("alice", "plane1", "teacher1"));
    }

    @Test
    void book_shouldMoveParticipantFromAvailableToBookings() {
        var slot = emptySlot();
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT));

        var event = new BookingEvent.ParticipantBooked("slot1", "alice", ParticipantType.STUDENT, "booking1");
        var updated = slot.book(event);

        assertFalse(updated.isWaiting("alice", ParticipantType.STUDENT));
        assertEquals(1, updated.bookings().size());
    }

    @Test
    void findBooking_shouldReturnParticipantsForBookingId() {
        var slot = emptySlot();
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "plane1", ParticipantType.AIRCRAFT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "teacher1", ParticipantType.INSTRUCTOR));

        slot = slot.book(new BookingEvent.ParticipantBooked("slot1", "alice", ParticipantType.STUDENT, "booking1"));
        slot = slot.book(new BookingEvent.ParticipantBooked("slot1", "plane1", ParticipantType.AIRCRAFT, "booking1"));
        slot = slot.book(new BookingEvent.ParticipantBooked("slot1", "teacher1", ParticipantType.INSTRUCTOR, "booking1"));

        var found = slot.findBooking("booking1");
        assertEquals(3, found.size());
    }

    @Test
    void findBooking_shouldReturnEmptyForUnknownId() {
        var slot = emptySlot();
        assertTrue(slot.findBooking("nonexistent").isEmpty());
    }

    @Test
    void cancelBooking_shouldRemoveAllParticipantsOfBooking() {
        var slot = emptySlot();
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "alice", ParticipantType.STUDENT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "plane1", ParticipantType.AIRCRAFT));
        slot.reserve(new BookingEvent.ParticipantMarkedAvailable("slot1", "teacher1", ParticipantType.INSTRUCTOR));

        slot = slot.book(new BookingEvent.ParticipantBooked("slot1", "alice", ParticipantType.STUDENT, "booking1"));
        slot = slot.book(new BookingEvent.ParticipantBooked("slot1", "plane1", ParticipantType.AIRCRAFT, "booking1"));
        slot = slot.book(new BookingEvent.ParticipantBooked("slot1", "teacher1", ParticipantType.INSTRUCTOR, "booking1"));

        var updated = slot.cancelBooking("booking1");

        assertTrue(updated.bookings().isEmpty());
        // Cancellation does NOT re-add to available
        assertTrue(updated.available().isEmpty());
    }
}

