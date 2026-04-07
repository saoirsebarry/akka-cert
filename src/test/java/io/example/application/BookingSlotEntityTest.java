package io.example.application;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.application.BookingSlotEntity.Command.BookReservation;
import io.example.application.BookingSlotEntity.Command.MarkSlotAvailable;
import io.example.application.BookingSlotEntity.Command.UnmarkSlotAvailable;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookingSlotEntityTest {

    // Use a future slot ID so bookings pass the future-date validation
    private static final String FUTURE_SLOT_ID = "2030-06-15-10";
    private static final String PAST_SLOT_ID = "2020-01-01-10";

    private static final Participant STUDENT = new Participant("alice", ParticipantType.STUDENT);
    private static final Participant AIRCRAFT = new Participant("superplane", ParticipantType.AIRCRAFT);
    private static final Participant INSTRUCTOR = new Participant("superteacher", ParticipantType.INSTRUCTOR);

    @Test
    void markSlotAvailable_shouldPersistEvent() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        var result = testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));

        assertTrue(result.isReply());
        assertEquals(Done.done(), result.getReply());
        assertTrue(result.didPersistEvents());

        var event = result.getNextEventOfType(BookingEvent.ParticipantMarkedAvailable.class);
        assertEquals(FUTURE_SLOT_ID, event.slotId());
        assertEquals("alice", event.participantId());
        assertEquals(ParticipantType.STUDENT, event.participantType());
    }

    @Test
    void markSlotAvailable_shouldRejectDuplicate() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        // First mark succeeds
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));

        // Second mark for same participant should fail
        var result = testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));

        assertTrue(result.isError());
        assertTrue(result.getError().contains("already marked available"));
    }

    @Test
    void unmarkSlotAvailable_shouldPersistEvent() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        // First mark available
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));

        // Then unmark
        var result = testKit.method(BookingSlotEntity::unmarkSlotAvailable)
                .invoke(new UnmarkSlotAvailable(STUDENT));

        assertTrue(result.isReply());
        assertTrue(result.didPersistEvents());

        var event = result.getNextEventOfType(BookingEvent.ParticipantUnmarkedAvailable.class);
        assertEquals("alice", event.participantId());
    }

    @Test
    void unmarkSlotAvailable_shouldRejectIfNotAvailable() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        var result = testKit.method(BookingSlotEntity::unmarkSlotAvailable)
                .invoke(new UnmarkSlotAvailable(STUDENT));

        assertTrue(result.isError());
        assertTrue(result.getError().contains("not marked available"));
    }

    @Test
    void bookSlot_shouldPersistThreeEvents() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        // Mark all three participants available
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(AIRCRAFT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(INSTRUCTOR));

        // Book the slot
        var result = testKit.method(BookingSlotEntity::bookSlot)
                .invoke(new BookReservation("alice", "superplane", "superteacher", "booking1"));

        assertTrue(result.isReply());
        assertEquals(Done.done(), result.getReply());
        assertTrue(result.didPersistEvents());
        assertEquals(3, result.getAllEvents().size());

        var studentBooked = result.getNextEventOfType(BookingEvent.ParticipantBooked.class);
        assertEquals("alice", studentBooked.participantId());
        assertEquals(ParticipantType.STUDENT, studentBooked.participantType());
        assertEquals("booking1", studentBooked.bookingId());

        var aircraftBooked = result.getNextEventOfType(BookingEvent.ParticipantBooked.class);
        assertEquals("superplane", aircraftBooked.participantId());
        assertEquals(ParticipantType.AIRCRAFT, aircraftBooked.participantType());

        var instructorBooked = result.getNextEventOfType(BookingEvent.ParticipantBooked.class);
        assertEquals("superteacher", instructorBooked.participantId());
        assertEquals(ParticipantType.INSTRUCTOR, instructorBooked.participantType());
    }

    @Test
    void bookSlot_shouldMoveParticipantsFromAvailableToBooked() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(AIRCRAFT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(INSTRUCTOR));

        testKit.method(BookingSlotEntity::bookSlot)
                .invoke(new BookReservation("alice", "superplane", "superteacher", "booking1"));

        // Check state: available should be empty, bookings should have 3
        var stateResult = testKit.method(BookingSlotEntity::getSlot).invoke();
        Timeslot state = stateResult.getReply();

        assertTrue(state.available().isEmpty());
        assertEquals(3, state.bookings().size());
    }

    @Test
    void bookSlot_shouldRejectWhenNotAllAvailable() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        // Only mark student available — missing aircraft and instructor
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));

        var result = testKit.method(BookingSlotEntity::bookSlot)
                .invoke(new BookReservation("alice", "superplane", "superteacher", "booking1"));

        assertTrue(result.isError());
        assertTrue(result.getError().contains("Not all participants are available"));
    }

    @Test
    void bookSlot_shouldRejectPastSlot() {
        var testKit = EventSourcedTestKit.of(PAST_SLOT_ID, BookingSlotEntity::new);

        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(AIRCRAFT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(INSTRUCTOR));

        var result = testKit.method(BookingSlotEntity::bookSlot)
                .invoke(new BookReservation("alice", "superplane", "superteacher", "booking1"));

        assertTrue(result.isError());
        assertTrue(result.getError().contains("Cannot book a slot in the past"));
    }

    @Test
    void cancelBooking_shouldPersistThreeEvents() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        // Setup: mark available and book
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(AIRCRAFT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(INSTRUCTOR));
        testKit.method(BookingSlotEntity::bookSlot)
                .invoke(new BookReservation("alice", "superplane", "superteacher", "booking1"));

        // Cancel the booking
        var result = testKit.method(BookingSlotEntity::cancelBooking)
                .invoke("booking1");

        assertTrue(result.isReply());
        assertEquals(Done.done(), result.getReply());
        assertTrue(result.didPersistEvents());
        assertEquals(3, result.getAllEvents().size());

        // All 3 events should be ParticipantCanceled
        for (Object evt : result.getAllEvents()) {
            assertInstanceOf(BookingEvent.ParticipantCanceled.class, evt);
            assertEquals("booking1", ((BookingEvent.ParticipantCanceled) evt).bookingId());
        }
    }

    @Test
    void cancelBooking_shouldClearBookingsFromState() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(AIRCRAFT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(INSTRUCTOR));
        testKit.method(BookingSlotEntity::bookSlot)
                .invoke(new BookReservation("alice", "superplane", "superteacher", "booking1"));

        testKit.method(BookingSlotEntity::cancelBooking).invoke("booking1");

        // State should have no bookings and no available participants
        var stateResult = testKit.method(BookingSlotEntity::getSlot).invoke();
        Timeslot state = stateResult.getReply();

        assertTrue(state.bookings().isEmpty());
        assertTrue(state.available().isEmpty());
    }

    @Test
    void cancelBooking_shouldRejectUnknownBooking() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        var result = testKit.method(BookingSlotEntity::cancelBooking)
                .invoke("nonexistent");

        assertTrue(result.isError());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void getSlot_shouldReturnEmptyStateInitially() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        var result = testKit.method(BookingSlotEntity::getSlot).invoke();

        assertTrue(result.isReply());
        Timeslot state = result.getReply();
        assertTrue(state.available().isEmpty());
        assertTrue(state.bookings().isEmpty());
    }

    @Test
    void getSlot_shouldReturnAvailableParticipants() {
        var testKit = EventSourcedTestKit.of(FUTURE_SLOT_ID, BookingSlotEntity::new);

        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(STUDENT));
        testKit.method(BookingSlotEntity::markSlotAvailable)
                .invoke(new MarkSlotAvailable(AIRCRAFT));

        var result = testKit.method(BookingSlotEntity::getSlot).invoke();
        Timeslot state = result.getReply();

        assertEquals(2, state.available().size());
        assertTrue(state.bookings().isEmpty());
    }
}

