package com.mobility.core.dispatch.suggest;

import java.util.EnumMap;
import java.util.Map;

import com.mobility.core.dispatch.suggest.AssignmentStrategy.Point;
import com.mobility.core.driver.HomeZone;

/** Approximate centres of Phnom Penh's khans, where a driver's day starts when they have no earlier trip. */
final class HomeZoneCentres {

	/** Independence Monument: for drivers without a home zone. */
	static final Point CITY_CENTRE = new Point(11.5564, 104.9282);

	private static final Map<HomeZone, Point> CENTRES = new EnumMap<>(HomeZone.class);

	static {
		CENTRES.put(HomeZone.CHAMKAR_MON, new Point(11.5450, 104.9200));
		CENTRES.put(HomeZone.DAUN_PENH, new Point(11.5700, 104.9230));
		CENTRES.put(HomeZone.PRAMPIR_MAKARA, new Point(11.5650, 104.9100));
		CENTRES.put(HomeZone.TUOL_KOUK, new Point(11.5800, 104.9000));
		CENTRES.put(HomeZone.DANGKAO, new Point(11.5000, 104.8700));
		CENTRES.put(HomeZone.MEAN_CHEY, new Point(11.5300, 104.9300));
		CENTRES.put(HomeZone.RUSSEY_KEO, new Point(11.6200, 104.8900));
		CENTRES.put(HomeZone.SEN_SOK, new Point(11.5900, 104.8500));
		CENTRES.put(HomeZone.POR_SENCHEY, new Point(11.5400, 104.8300));
		CENTRES.put(HomeZone.CHROY_CHANGVAR, new Point(11.6000, 104.9300));
		CENTRES.put(HomeZone.PREK_PNOV, new Point(11.6600, 104.8700));
		CENTRES.put(HomeZone.CHBAR_AMPOV, new Point(11.5300, 104.9600));
		CENTRES.put(HomeZone.BOENG_KENG_KANG, new Point(11.5520, 104.9250));
		CENTRES.put(HomeZone.KAMBOUL, new Point(11.5100, 104.7700));
	}

	private HomeZoneCentres() {
	}

	static Point of(HomeZone zone) {
		return zone == null ? CITY_CENTRE : CENTRES.getOrDefault(zone, CITY_CENTRE);
	}
}
