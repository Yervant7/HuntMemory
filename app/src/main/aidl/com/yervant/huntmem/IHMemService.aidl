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

import com.yervant.huntmem.ILuaUiCallback;

/**
 * Root service for process memory inspection and editing via HMKPM direct MMU translation and V2P residency checks.
 */
interface IHMemService {

    /** true if HMKPM module responds to PROBE. */
    boolean nativeIsHmkpmAvailable();

    /** Returns JSON string with HMKPM version, code, and features bitmask. */
    String nativeGetHmkpmVersionInfo();

    /** Translates virtual addresses to physical addresses in batch via kernel page table walk. */
    long[] nativeTranslateV2P(int pid, in long[] addresses);

    String nativeReadMemory(int pid, long address, String valueType);

    int nativeWriteMemory(int pid, long address, String value, String valueType);

    void nativeClearSession(String sessionId);

    int nativeBatchWriteBinary(int pid, in byte[] payload);

    int nativeFreezeAddress(int pid, long address, String value, String valueType);

    boolean nativeUnfreezeAddress(int pid, long address);

    int nativeUnfreezeAll();

    int nativeWriteObscured(int pid, long address, String value, String obscuredType);

    int nativeWriteBigDouble(int pid, long address, String value);

    boolean nativeIsAddressFrozen(int pid, long address);

    String nativeRunLuaScript(int pid, String script, ILuaUiCallback callback);

    void nativeCancelLuaScript();

    long nativeGetModuleBase(int pid, String moduleName);

    long nativeResolvePointerChain(int pid, String baseExpr, String offsetsJson);

    byte[] nativeGetMemoryMapsBinary(int pid, boolean require_read, boolean require_write, boolean include_swapped, long min_size, String filter_types, String custom);

    int nativeGetResultsCount(String sessionId);

    byte[] nativeGetResultsPage(String sessionId, int offset, int limit);

    byte[] nativeRefreshResultsPage(int pid, String sessionId, int offset, int limit);

    int nativeScanMemoryFast(int pid, String sessionId, String value, String valueType, String filterTypes, String customFilter, String operator, int mapFlags, long minSize);

    int nativeScanRangeFast(int pid, String sessionId, String minValue, String maxValue, String valueType, String filterTypes, String customFilter, int mapFlags, long minSize);

    int nativeScanGroupFast(int pid, String sessionId, String groupSpec, String valueType, String filterTypes, String customFilter, int mapFlags, long minSize);

    int nativeScanObscuredFast(int pid, String sessionId, String value, String obscuredType, String filterTypes, String customFilter, int mapFlags, long minSize);

    int nativeScanBigDoubleFast(int pid, String sessionId, String value, String filterTypes, String customFilter, int mapFlags, long minSize);

    int nativeFilterMatchesFast(int pid, String sessionId, String targetValue, String operator);

    int nativeFilterRangeMatchesFast(int pid, String sessionId, String minValue, String maxValue);

    int nativeFilterObscuredMatchesFast(int pid, String sessionId, String targetValue, String obscuredType);

    int nativeFilterBigDoubleMatchesFast(int pid, String sessionId, String targetValue);

    void nativeSetScanEngineMode(int mode);

    int nativeGetScanEngineMode();

    void nativeCancelScan();

    boolean nativeSaveSessionCheckpoint(String sessionId);

    int nativeUndoSession(String sessionId);

    int nativeGetSessionUndoCount(String sessionId);

    boolean nativeIsSessionSaved(String sessionId);

    int nativeCloneSession(String sourceSessionId, String targetSessionId);

    int nativeCopyMatchesToSession(String sourceSessionId, String targetSessionId, in long[] addresses);
}

