package com.mobility.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.event.EventListener;

/** Records the application's own domain events (published on server threads) for assertions. */
public class CapturedEvents {

	private final List<Object> events = new CopyOnWriteArrayList<>();

	@EventListener
	void on(Object event) {
		if (event.getClass().getName().startsWith("com.mobility.core.")) {
			events.add(event);
		}
	}

	public <T> List<T> ofType(Class<T> type) {
		return events.stream().filter(type::isInstance).map(type::cast).toList();
	}
}
