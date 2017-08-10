/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.memory.UnsafeUtil.unsafe;
import static com.yahoo.sketches.hll.HllUtil.EMPTY;
import static com.yahoo.sketches.hll.HllUtil.RESIZE_DENOM;
import static com.yahoo.sketches.hll.HllUtil.RESIZE_NUMER;
import static com.yahoo.sketches.hll.PreambleUtil.extractAuxCount;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgArr;
import static com.yahoo.sketches.hll.PreambleUtil.insertAuxCount;
import static com.yahoo.sketches.hll.PreambleUtil.insertLgArr;

import com.yahoo.memory.Memory;
import com.yahoo.memory.MemoryRequestServer;
import com.yahoo.memory.WritableMemory;
import com.yahoo.sketches.SketchesArgumentException;
import com.yahoo.sketches.SketchesStateException;

/**
 * @author Lee Rhodes
 */
class DirectAuxHashMap implements AuxHashMap {
  private final DirectHllArray host; //hosts the working Memory

  DirectAuxHashMap(final DirectHllArray host, final boolean initialize) {
    this.host = host;
    final int lgConfigK = host.lgConfigK;
    final Object memObj = host.memObj;
    final long memAdd = host.memAdd;
    final int initLgArrInts = HllUtil.LG_AUX_ARR_INTS[lgConfigK];
    final int memLgArrInts = extractLgArr(memObj, memAdd);
    if (initialize) {
      insertLgArr(memObj, memAdd, initLgArrInts);
      host.wmem.clear(host.auxArrOffset, 4 << initLgArrInts);
    } else {
      assert memLgArrInts >= initLgArrInts;
    }
  }

  @Override
  public DirectAuxHashMap copy() { //a no-op
    return null;
  }

  @Override
  public int getAuxCount() {
    return extractAuxCount(host.memObj, host.memAdd);
  }

  @Override
  public int[] getAuxIntArr() {
    return null;
  }

  @Override
  public int getCompactedSizeBytes() {
    return getAuxCount() << 2;
  }

  @Override
  public PairIterator getIterator() {
    return new DirectAuxIterator(host.wmem, host.auxArrOffset, 1 << getLgAuxArrInts());
  }

  @Override
  public int getLgAuxArrInts() {
    return extractLgArr(host.memObj, host.memAdd);
  }

  @Override
  public int getUpdatableSizeBytes() {
    return 4 << getLgAuxArrInts();
  }

  @Override
  public void mustAdd(final int slotNo, final int value) {
    final int index = find(host, slotNo);
    final int pair = HllUtil.pair(slotNo, value);
    if (index >= 0) {
      final String pairStr = HllUtil.pairString(pair);
      throw new SketchesStateException("Found a slotNo that should not be there: " + pairStr);
    }
    //Found empty entry
    unsafe.putInt(host.memObj, host.memAdd + host.auxArrOffset + (~index << 2), pair);
    int auxCount = extractAuxCount(host.memObj, host.memAdd);
    insertAuxCount(host.memObj, host.memAdd, ++auxCount);
    checkGrow(host, auxCount);
  }

  @Override
  public int mustFindValueFor(final int slotNo) {
    final int index = find(host, slotNo);
    if (index >= 0) {
      final int pair = unsafe.getInt(host.memObj, host.memAdd + host.auxArrOffset + (index << 2));
      return HllUtil.getValue(pair);
    }
    throw new SketchesStateException("SlotNo not found: " + slotNo);
  }

  @Override
  public void mustReplace(final int slotNo, final int value) {
    final int index = find(host, slotNo);
    if (index >= 0) {
      unsafe.putInt(host.memObj, host.memAdd + host.auxArrOffset + (index << 2), HllUtil.pair(slotNo, value));
      return;
    }
    final String pairStr = HllUtil.pairString(HllUtil.pair(slotNo, value));
    throw new SketchesStateException("Pair not found: " + pairStr);
  }

  //Searches the Aux arr hash table (embedded in Memory) for an empty or a matching slotNo
  //  depending on the context.
  //If entire entry is empty, returns one's complement of index = found empty.
  //If entry contains given slotNo, returns its index = found slotNo.
  //Continues searching.
  //If the probe comes back to original index, throws an exception.
  private static final int find(final DirectHllArray host, final int slotNo) {
    final int lgAuxArrInts = extractLgArr(host.memObj, host.memAdd);
    assert lgAuxArrInts < host.lgConfigK : lgAuxArrInts;
    final int auxInts = 1 << lgAuxArrInts;
    final int auxArrMask = auxInts - 1;
    final int configKmask = (1 << host.lgConfigK) - 1;
    int probe = slotNo & auxArrMask;
    final int loopIndex = probe;
    do {
      final int arrVal = unsafe.getInt(host.memObj, host.memAdd + host.auxArrOffset + (probe << 2));
      if (arrVal == EMPTY) {
        return ~probe; //empty
      }
      else if (slotNo == (arrVal & configKmask)) { //found given slotNo
        return probe; //return aux array index
      }
      final int stride = (slotNo >>> lgAuxArrInts) | 1;
      probe = (probe + stride) & auxArrMask;
    } while (probe != loopIndex);
    throw new SketchesArgumentException("Key not found and no empty slots!");
  }

  private static final void checkGrow(final DirectHllArray host, final int auxCount) {
    int lgAuxArrInts = extractLgArr(host.memObj, host.memAdd);
    if ((RESIZE_DENOM * auxCount) > (RESIZE_NUMER * (1 << lgAuxArrInts))) {
      insertLgArr(host.memObj, host.memAdd, ++lgAuxArrInts);
      final long requestBytes = host.auxArrOffset + (4 << lgAuxArrInts);
      final long oldCapBytes = host.wmem.getCapacity();
      if (requestBytes > oldCapBytes) {
        final MemoryRequestServer svr = host.wmem.getMemoryRequestServer();
        final WritableMemory newWmem = svr.request(requestBytes);
        host.wmem.copyTo(0, newWmem, 0, oldCapBytes); //also copies old auxArr
        svr.requestClose(host.wmem, newWmem);
        host.updateMemory(newWmem);
      }
      growAuxSpace(host);
    }
  }

  //lgArr must have been incremented and there must be sufficient space.
  private static final void growAuxSpace(final DirectHllArray host) {
    final int auxArrInts = 1 << extractLgArr(host.memObj, host.memAdd);
    final int[] oldArray = new int[auxArrInts];
    host.wmem.getIntArray(host.auxArrOffset, oldArray, 0, auxArrInts);
    final int configKmask = (1 << host.lgConfigK) - 1;
    host.wmem.clear(host.auxArrOffset, auxArrInts << 2);
    for (int i = 0; i < auxArrInts; i++) {
      final int fetched = oldArray[i];
      if (fetched != EMPTY) {
        //find empty in new array
        final int index = find(host, fetched & configKmask);
        unsafe.putInt(host.memObj, host.memAdd + host.auxArrOffset + (~index << 2), fetched);
      }
    }
  }

  //ITERATOR
  final class DirectAuxIterator extends IntMemoryPairIterator {

    DirectAuxIterator(final Memory mem, final long offsetBytes, final int lengthPairs) {
      super(mem, offsetBytes, lengthPairs);
    }

    @Override
    int pair() {
      return unsafe.getInt(memObj, memAdd + offsetBytes + (index << 2));
    }
  }


  //static void println(final String s) { System.out.println(s); }
}