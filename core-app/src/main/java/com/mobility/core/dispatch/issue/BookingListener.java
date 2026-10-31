package com.mobility.core.dispatch.issue;

import com.mobility.core.booking.BookingEvents.TripCancelled;
import com.mobility.core.booking.BookingEvents.TripStatusChanged;
import com.mobility.core.booking.TripStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the exception queue in step with trips: a cancelled trip (or no-show) closes its exceptions; once the driver
 * has arrived, risk and confirmation exceptions no longer apply. Runs after booking's transaction commits.
 */
@Component
class BookingListener {

	private final ExceptionService exceptions;

	BookingListener(ExceptionService exceptions) {
		this.exceptions = exceptions;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void on(TripCancelled event) {
		exceptions.autoResolve(event.tripId());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void on(TripStatusChanged event) {
		if (event.to() == TripStatus.DRIVER_ARRIVED || event.to() == TripStatus.IN_PROGRESS || event.to() == TripStatus.COMPLETED) {
			exceptions.autoResolve(event.tripId(), ExceptionType.AT_RISK, ExceptionType.UNCONFIRMED,
					ExceptionType.BACKUP_UNCONFIRMED, ExceptionType.NO_BACKUP, ExceptionType.UNASSIGNED);
		}
	}
}
