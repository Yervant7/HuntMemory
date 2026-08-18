/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yervant.huntmem;

/**
 * Root service for memory reading/writing via pagemap + HMKPM.
 */
interface IHMemService {

    /** true if HMKPM module responds to PROBE. */
    boolean nativeIsHmkpmAvailable();

    String nativeReadMemory(int pid, long address, String valueType);

    int nativeWriteMemory(int pid, long address, String value, String valueType);

    String nativeGetMemoryMaps(int pid, boolean require_read, boolean require_write, boolean include_swapped, long min_size, String filter_types, String custom);

    String nativeScanMemory(int pid, String sessionId, String value, String valueType, String regionsJson, String operator);

    String nativeScanRange(int pid, String sessionId, String minValue, String maxValue, String valueType, String regionsJson);

    String nativeScanGroup(int pid, String sessionId, String groupSpec, String valueType, String regionsJson);

    String nativeFilterMatches(int pid, String sessionId, String targetValue, String operator);

    String nativeFilterRangeMatches(int pid, String sessionId, String minValue, String maxValue);

    void nativeClearSession(String sessionId);

    int nativeBatchWrite(int pid, String writesJson);

    int nativeFreezeAddress(int pid, long address, String value, String valueType);

    boolean nativeUnfreezeAddress(long address);

    int nativeUnfreezeAll();

    String nativeScanObscured(int pid, String sessionId, String value, String obscuredType, String regionsJson);

    String nativeScanBigDouble(int pid, String sessionId, String value, String regionsJson);

    String nativeFilterObscuredMatches(int pid, String sessionId, String targetValue, String obscuredType);

    String nativeFilterBigDoubleMatches(int pid, String sessionId, String targetValue);

    int nativeWriteObscured(int pid, long address, String value, String obscuredType);

    int nativeWriteBigDouble(int pid, long address, String value);

    boolean nativeIsAddressFrozen(long address);
}
