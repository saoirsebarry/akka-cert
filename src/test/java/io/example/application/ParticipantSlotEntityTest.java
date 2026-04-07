package io.example.application;

import akka.Done;
import akka.javasdk.testkit.EventSourcedTestKit;
import io.example.application.ParticipantSlotEntity.Commands.Book;
import io.example.application.ParticipantSlotEntity.Commands.Cancel;
import io.example.application.ParticipantSlotEntity.Commands.MarkAvailable;
import io.example.application.ParticipantSlotEntity.Commands.UnmarkAvailable;
import io.example.application.ParticipantSlotEntity.Event;
import io.example.application.ParticipantSlotEntity.State;
import io.example.domain.Participant.ParticipantType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParticipantSlotEntityTest {

    private static final String SLOT_ID = "2030-06-15-10";
    private static final String PARTICIPANT_ID = "alice";
    private static final ParticipantType TYPE = ParticipantType.STUDENT;
    private static final String ENTITY_ID = SLOT_ID + "-" + PARTICIPANT_ID;

    @Test
    void markAvailable_shouldPersistEventAndUpdateState() {
        var testKit = EventSourcedTestKit.of(ENTITY_ID, ParticipantSlotEntity::new);

        var result = testKit.method(ParticipantSlotEntity::markAvailable)
                .invoke(new MarkAvailable(SLOT_ID, PARTICIPANT_ID, TYPE));

        assertTrue(result.isReply());
        assertEquals(Done.done(), result.getReply());
        assertTrue(result.didPersistEvents());

        var event = result.getNextEventOfType(Event.MarkedAvailable.class);
        assertEquals(SLOT_ID, event.slotId());
        assertEquals(PARTICIPANT_ID, event.participantId());
        assertEquals(TYPE, event.participantType());

        State state = (State) result.getUpdatedState();
        assertEquals("available", state.status());
        assertEquals(SLOT_ID, state.slotId());
        assertEquals(PARTICIPANT_ID, state.participantId());
    }

    @Test
    void unmarkAvailable_shouldPersistEventAndUpdateState() {
        var testKit = EventSourcedTestKit.of(ENTITY_ID, ParticipantSlotEntity::new);

        // First mark available
        testKit.method(ParticipantSlotEntity::markAvailable)
                .invoke(new MarkAvailable(SLOT_ID, PARTICIPANT_ID, TYPE));

        // Then unmark
        var result = testKit.method(ParticipantSlotEntity::unmarkAvailable)
                .invoke(new UnmarkAvailable(SLOT_ID, PARTICIPANT_ID, TYPE));

        assertTrue(result.isReply());
        assertTrue(result.didPersistEvents());

        var event = result.getNextEventOfType(Event.UnmarkedAvailable.class);
        assertEquals(PARTICIPANT_ID, event.participantId());

        State state = (State) result.getUpdatedState();
        assertEquals("unavailable", state.status());
    }

    @Test
    void book_shouldPersistEventAndUpdateState() {
        var testKit = EventSourcedTestKit.of(ENTITY_ID, ParticipantSlotEntity::new);

        var result = testKit.method(ParticipantSlotEntity::book)
                .invoke(new Book(SLOT_ID, PARTICIPANT_ID, TYPE, "booking1"));

        assertTrue(result.isReply());
        assertTrue(result.didPersistEvents());

        var event = result.getNextEventOfType(Event.Booked.class);
        assertEquals(PARTICIPANT_ID, event.participantId());
        assertEquals("booking1", event.bookingId());

        State state = (State) result.getUpdatedState();
        assertEquals("booked", state.status());
        assertEquals(SLOT_ID, state.slotId());
    }

    @Test
    void cancel_shouldPersistEventAndUpdateState() {
        var testKit = EventSourcedTestKit.of(ENTITY_ID, ParticipantSlotEntity::new);

        // First book
        testKit.method(ParticipantSlotEntity::book)
                .invoke(new Book(SLOT_ID, PARTICIPANT_ID, TYPE, "booking1"));

        // Then cancel
        var result = testKit.method(ParticipantSlotEntity::cancel)
                .invoke(new Cancel(SLOT_ID, PARTICIPANT_ID, TYPE, "booking1"));

        assertTrue(result.isReply());
        assertTrue(result.didPersistEvents());

        var event = result.getNextEventOfType(Event.Canceled.class);
        assertEquals(PARTICIPANT_ID, event.participantId());
        assertEquals("booking1", event.bookingId());

        State state = (State) result.getUpdatedState();
        assertEquals("canceled", state.status());
    }

    @Test
    void fullLifecycle_availableThenBookedThenCanceled() {
        var testKit = EventSourcedTestKit.of(ENTITY_ID, ParticipantSlotEntity::new);

        // Mark available
        var r1 = testKit.method(ParticipantSlotEntity::markAvailable)
                .invoke(new MarkAvailable(SLOT_ID, PARTICIPANT_ID, TYPE));
        assertEquals("available", ((State) r1.getUpdatedState()).status());

        // Book
        var r2 = testKit.method(ParticipantSlotEntity::book)
                .invoke(new Book(SLOT_ID, PARTICIPANT_ID, TYPE, "booking1"));
        assertEquals("booked", ((State) r2.getUpdatedState()).status());

        // Cancel
        var r3 = testKit.method(ParticipantSlotEntity::cancel)
                .invoke(new Cancel(SLOT_ID, PARTICIPANT_ID, TYPE, "booking1"));
        assertEquals("canceled", ((State) r3.getUpdatedState()).status());
    }
}

