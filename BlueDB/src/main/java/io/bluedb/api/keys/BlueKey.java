package io.bluedb.api.keys;

import java.io.Serializable;
import java.util.Comparator;

public interface BlueKey extends Serializable, Comparable<BlueKey> {

	static final Comparator<Object> nullSafeClassComparator = Comparator.nullsLast(BlueKey::unsafeCompareCanonicalClassNames);

	@Override
	public abstract int hashCode();

	@Override
	public abstract boolean equals(Object object);

	@Override
	public abstract int compareTo(BlueKey other);
	
	public long getGroupingNumber();
	
	default public Long getLongIdIfPresent() {
		return null;
	}

	default public Integer getIntegerIdIfPresent() {
		return null;
	}

	default boolean isInRange(long min, long max) {
		return getGroupingNumber() >= min && getGroupingNumber() <= max;
	}

	default int compareClasses(BlueKey other) {
		return nullSafeClassComparator.compare(this, other);
	}

	public static int compareCanonicalClassNames(Object first, Object second) {
		return nullSafeClassComparator.compare(first,  second);
	}

	public static int unsafeCompareCanonicalClassNames(Object first, Object second) {
		String firstClassName = first.getClass().getCanonicalName();
		String secondClassName = second.getClass().getCanonicalName();
		return firstClassName.compareTo(secondClassName);
	}
}
