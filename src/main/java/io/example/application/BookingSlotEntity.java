package io.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.example.domain.BookingEvent;
import io.example.domain.Participant;
import io.example.domain.Participant.ParticipantType;
import io.example.domain.Timeslot;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(id = "booking-slot")
public class BookingSlotEntity extends EventSourcedEntity<Timeslot, BookingEvent> {

    private final String entityId;
    private static final Logger logger = LoggerFactory.getLogger(BookingSlotEntity.class);

    public BookingSlotEntity(EventSourcedEntityContext context) {
        this.entityId = context.entityId();
    }

    public Effect<Done> markSlotAvailable(Command.MarkSlotAvailable cmd) {
        var participant = cmd.participant();
        if (currentState().isWaiting(participant.id(), participant.participantType())) {
            return effects().error("Participant " + participant.id() + " is already marked available");
        }

        var event = new BookingEvent.ParticipantMarkedAvailable(
                entityId, participant.id(), participant.participantType());
        return effects().persist(event).thenReply(__ -> Done.done());
    }

    public Effect<Done> unmarkSlotAvailable(Command.UnmarkSlotAvailable cmd) {
        var participant = cmd.participant();
        if (!currentState().isWaiting(participant.id(), participant.participantType())) {
            return effects().error("Participant " + participant.id() + " is not marked available");
        }

        var event = new BookingEvent.ParticipantUnmarkedAvailable(
                entityId, participant.id(), participant.participantType());
        return effects().persist(event).thenReply(__ -> Done.done());
    }

    // NOTE: booking a slot should produce 3
    // `ParticipantBooked` events
    public Effect<Done> bookSlot(Command.BookReservation cmd) {
        // Validate the slot is in the future
        if (!isSlotInFuture(entityId)) {
            return effects().error("Cannot book a slot in the past");
        }

        // Validate all 3 participants are available
        if (!currentState().isBookable(cmd.studentId(), cmd.aircraftId(), cmd.instructorId())) {
            return effects().error("Not all participants are available for this slot");
        }

        // Emit 3 ParticipantBooked events — one per participant
        var studentBooked = new BookingEvent.ParticipantBooked(
                entityId, cmd.studentId(), ParticipantType.STUDENT, cmd.bookingId());
        var aircraftBooked = new BookingEvent.ParticipantBooked(
                entityId, cmd.aircraftId(), ParticipantType.AIRCRAFT, cmd.bookingId());
        var instructorBooked = new BookingEvent.ParticipantBooked(
                entityId, cmd.instructorId(), ParticipantType.INSTRUCTOR, cmd.bookingId());

        return effects()
                .persist(studentBooked, aircraftBooked, instructorBooked)
                .thenReply(__ -> Done.done());
    }

    // NOTE: canceling a booking should produce 3
    // `ParticipantCanceled` events
    public Effect<Done> cancelBooking(String bookingId) {
        List<Timeslot.Booking> bookings = currentState().findBooking(bookingId);

        if (bookings.isEmpty()) {
            return effects().error("Booking " + bookingId + " not found");
        }

        List<BookingEvent> cancelEvents = bookings.stream()
                .map(b -> (BookingEvent) new BookingEvent.ParticipantCanceled(
                        entityId,
                        b.participant().id(),
                        b.participant().participantType(),
                        bookingId))
                .toList();

        return effects()
                .persistAll(cancelEvents)
                .thenReply(__ -> Done.done());
    }

    public ReadOnlyEffect<Timeslot> getSlot() {
        return effects().reply(currentState());
    }

    @Override
    public Timeslot emptyState() {
        return new Timeslot(
                // NOTE: these are just estimates for capacity based on it being a sample
                HashSet.newHashSet(10), HashSet.newHashSet(10));
    }

    @Override
    public Timeslot applyEvent(BookingEvent event) {
        return switch (event) {
            case BookingEvent.ParticipantMarkedAvailable e -> currentState().reserve(e);
            case BookingEvent.ParticipantUnmarkedAvailable e -> currentState().unreserve(e);
            case BookingEvent.ParticipantBooked e -> currentState().book(e);
            case BookingEvent.ParticipantCanceled e -> currentState().cancelBooking(e.bookingId());
        };
    }

    private boolean isSlotInFuture(String slotId) {
        try {
            String[] parts = slotId.split("-");
            if (parts.length < 4) return false;
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            int hour = Integer.parseInt(parts[3]);
            return LocalDateTime.of(year, month, day, hour, 0).isAfter(LocalDateTime.now());
        } catch (Exception e) {
            logger.warn("Could not parse slot ID as date: {}", slotId);
            return false;
        }
    }

    public sealed interface Command {
        record MarkSlotAvailable(Participant participant) implements Command {
        }

        record UnmarkSlotAvailable(Participant participant) implements Command {
        }

        record BookReservation(
                String studentId, String aircraftId, String instructorId, String bookingId)
                implements Command {
        }
    }
}
