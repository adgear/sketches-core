/*
 * Copyright 2015, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */
package com.yahoo.sketches.theta;

import static com.yahoo.sketches.Family.objectToFamily;
import static com.yahoo.sketches.Family.stringToFamily;
import static com.yahoo.sketches.theta.CompactSketch.compactCachePart;
import static com.yahoo.sketches.theta.PreambleUtil.EMPTY_FLAG_MASK;
import static com.yahoo.sketches.theta.PreambleUtil.FAMILY_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.FLAGS_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.LG_ARR_LONGS_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.LG_NOM_LONGS_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.PREAMBLE_LONGS_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.P_FLOAT;
import static com.yahoo.sketches.theta.PreambleUtil.RETAINED_ENTRIES_INT;
import static com.yahoo.sketches.theta.PreambleUtil.SEED_HASH_SHORT;
import static com.yahoo.sketches.theta.PreambleUtil.SER_VER;
import static com.yahoo.sketches.theta.PreambleUtil.SER_VER_BYTE;
import static com.yahoo.sketches.theta.PreambleUtil.THETA_LONG;
import static com.yahoo.sketches.theta.PreambleUtil.checkSeedHashes;
import static com.yahoo.sketches.Util.*;
import static java.lang.Math.min;

import com.yahoo.sketches.Family;
import com.yahoo.sketches.memory.Memory;
import com.yahoo.sketches.memory.MemoryUtil;
import com.yahoo.sketches.memory.NativeMemory;
import com.yahoo.sketches.HashOperations;

/**
 * @author Lee Rhodes
 * @author Kevin Lang
 */
class DirectIntersection extends SetOperation implements Intersection {
  private final short seedHash_;
  private int lgArrLongs_; //current size of hash table
  private int curCount_; //curCount of HT, if < 0 means Universal Set (US) is true
  private long thetaLong_;
  private boolean empty_;
  
  private final int maxLgArrLongs_; //max size of hash table
  private final Memory mem_;

  
  /**
   * Construct a new Intersection target direct to the given destination Memory.
   * Called by SetOperation.Builder.
   * 
   * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See Seed</a>
   * @param dstMem destination Memory.  
   * <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   */
  DirectIntersection(long seed, Memory dstMem) {
    int preBytes = CONST_PREAMBLE_LONGS << 3;
    maxLgArrLongs_ = checkMaxLgArrLongs(dstMem);
    
    //build preamble and cache together in single Memory
    mem_ = dstMem;
    mem_.clear(0, preBytes); //clear only the preamble
    
    //load preamble into mem
    mem_.putByte(PREAMBLE_LONGS_BYTE, (byte) CONST_PREAMBLE_LONGS); //RF not used = 0
    mem_.putByte(SER_VER_BYTE, (byte) SER_VER);
    mem_.putByte(FAMILY_BYTE, (byte) stringToFamily("Intersection").getID());
    lgArrLongs_ = setLgArrLongs(MIN_LG_ARR_LONGS); //set initially to minimum, but don't clear.
    
    //flags: bigEndian = readOnly = compact = ordered = false;
    empty_ = setEmpty(false);
    
    seedHash_ = computeSeedHash(seed);
    mem_.putShort(SEED_HASH_SHORT, seedHash_);
    
    curCount_ = setCurCount(-1);
    
    mem_.putFloat(P_FLOAT, (float) 1.0);
    thetaLong_ = setThetaLong(Long.MAX_VALUE);
  }
  
  /**
   * Wrap an Intersection target around the given source Memory containing intersection data. 
   * @param srcMem The source Memory image.
   * <a href="{@docRoot}/resources/dictionary.html#mem">See Memory</a>
   * @param seed <a href="{@docRoot}/resources/dictionary.html#seed">See seed</a> 
   */
  DirectIntersection(Memory srcMem, long seed) {
    int preambleLongs = srcMem.getByte(PREAMBLE_LONGS_BYTE) & 0X3F;
    if (preambleLongs != CONST_PREAMBLE_LONGS) {
      throw new IllegalArgumentException("PreambleLongs must = 3.");
    }
    
    int serVer = srcMem.getByte(SER_VER_BYTE);
    if (serVer != 3) throw new IllegalArgumentException("Ser Version must = 3");
    
    Family.INTERSECTION.checkFamilyID(srcMem.getByte(FAMILY_BYTE));
    
    lgArrLongs_ = srcMem.getByte(LG_ARR_LONGS_BYTE); //current hash table size
    maxLgArrLongs_ = checkMaxLgArrLongs(srcMem);
    
    empty_ = srcMem.isAnyBitsSet(FLAGS_BYTE, (byte) EMPTY_FLAG_MASK);
    
    seedHash_ = computeSeedHash(seed);
    short seedHashMem = srcMem.getShort(SEED_HASH_SHORT);
    checkSeedHashes(seedHashMem, seedHash_); //check for seed hash conflict
    
    curCount_ = srcMem.getInt(RETAINED_ENTRIES_INT);
    thetaLong_ = srcMem.getLong(THETA_LONG);
    
    if (empty_) {
      if (curCount_ != 0) {
        throw new IllegalArgumentException(
            "srcMem empty state inconsistent with curCount: "+empty_+","+curCount_);
      }
      //empty = true AND curCount_ = 0: OK
    } //else empty = false, curCount could be anything
    mem_ = srcMem;
  }
  
  @Override
  @SuppressWarnings("null") //due to the state machine construction
  public void update(Sketch sketchIn) {
    
    //The Intersection State Machine
    boolean skInIsValidAndNonZero = ((sketchIn != null) && (sketchIn.getRetainedEntries(true) > 0));
    
    if ((curCount_ == 0) || !skInIsValidAndNonZero) {
      //The 1st Call (curCount  < 0) and sketchIn was either null or had zero entries.
      //The Nth Call (curCount == 0) and sketchIn was either null or had zero entries.
      //The Nth Call (curCount == 0) and sketchIn was valid with cnt > 0.
      //The Nth Call (curCount  > 0) and sketchIn was either null or had zero entries.
      //All future intersections result in zero data, but theta can still be reduced.
      //set curCount == 0
      if (sketchIn != null) {
        checkSeedHashes(seedHash_, sketchIn.getSeedHash());
        thetaLong_ = minThetaLong(sketchIn.getThetaLong());
        empty_ = setEmpty(empty_ | sketchIn.isEmpty());  //Empty rule
      } 
      else {
        //don't change theta
        empty_ = setEmpty(true);
      }
      curCount_ = setCurCount(0); //curCount was -1, must set to >= 0
      //No need for a HT.
    }
    else if (curCount_ < 0) { //virgin
      //The 1st Call and sketchIn was a valid with cnt > 0.
      //Clone the incoming sketch
      checkSeedHashes(seedHash_, sketchIn.getSeedHash());
      thetaLong_ = minThetaLong(sketchIn.getThetaLong());
      empty_ = setEmpty(empty_ | sketchIn.isEmpty());  //Empty rule
      
      curCount_ = setCurCount(sketchIn.getRetainedEntries(true));
      int newLgArrLongs = setLgArrLongs(computeMinLgArrLongsFromCount(curCount_));
      int preBytes = CONST_PREAMBLE_LONGS << 3;
      if (newLgArrLongs <= maxLgArrLongs_) { //OK
        lgArrLongs_ = setLgArrLongs(newLgArrLongs);
        mem_.clear(preBytes, 8 << lgArrLongs_);
      }
      else { //not enough space in dstMem
        
        throw new IllegalArgumentException(
            "Insufficient dstMem hash table space: "+(1<<newLgArrLongs)+" > "+(1<<lgArrLongs_));
      }
      
      //Then move data into HT
      moveDataToHT(sketchIn.getCache(), curCount_);
    }
    else { //curCount > 0
      //Nth Call: and and sketchIn was valid with cnt > 0.
      //Perform full intersect
      checkSeedHashes(seedHash_, sketchIn.getSeedHash());
      thetaLong_ = minThetaLong(sketchIn.getThetaLong());
      empty_ = setEmpty(empty_ | sketchIn.isEmpty());
      
      //Must perform full intersection
      // sets resulting hashTable, curCount and adjusts lgArrLongs
      performIntersect(sketchIn);
    }
  }

  @Override
  public CompactSketch getResult(boolean dstOrdered, Memory dstMem) {
    if (curCount_ < 0) {
      throw new IllegalStateException(
          "Calling getResult() with no intervening intersections is not a legal result.");
    }
    long[] compactCacheR;
    
    if (curCount_ == 0) {
      compactCacheR = new long[0];
      return CompactSketch.createCompactSketch(
          compactCacheR, empty_, seedHash_, curCount_, thetaLong_, dstOrdered, dstMem);
    } 
    //else curCount > 0
    int htLen = 1 << lgArrLongs_;
    long[] hashTable = new long[htLen];
    mem_.getLongArray(CONST_PREAMBLE_LONGS << 3, hashTable, 0, htLen);
    compactCacheR = compactCachePart(hashTable, lgArrLongs_, curCount_, thetaLong_, dstOrdered);
    
    //Create the CompactSketch
    return CompactSketch.createCompactSketch(
        compactCacheR, empty_, seedHash_, curCount_, thetaLong_, dstOrdered, dstMem);
  }
  
  @Override
  public CompactSketch getResult() {
    return getResult(true, null);
  }
  
  @Override
  public boolean hasResult() {
    return mem_.getInt(RETAINED_ENTRIES_INT) >= 0;
  }
  
  @Override
  public byte[] toByteArray() {
    int preBytes = CONST_PREAMBLE_LONGS << 3;
    int dataBytes = (curCount_ > 0)? 8 << lgArrLongs_ : 0;
    byte[] byteArrOut = new byte[preBytes + dataBytes];
    NativeMemory memOut = new NativeMemory(byteArrOut);
    
    //preamble
    memOut.putByte(PREAMBLE_LONGS_BYTE, (byte) CONST_PREAMBLE_LONGS); //RF not used = 0
    memOut.putByte(SER_VER_BYTE, (byte) SER_VER);
    memOut.putByte(FAMILY_BYTE, (byte) objectToFamily(this).getID());
    memOut.putByte(LG_NOM_LONGS_BYTE, (byte) 0); //bit used
    memOut.putByte(LG_ARR_LONGS_BYTE, (byte) lgArrLongs_);
    if (empty_) {
      memOut.setBits(FLAGS_BYTE, (byte) EMPTY_FLAG_MASK);
    } 
    else {
      memOut.clearBits(FLAGS_BYTE, (byte) EMPTY_FLAG_MASK);
    }
    memOut.putShort(SEED_HASH_SHORT, seedHash_);
    memOut.putInt(RETAINED_ENTRIES_INT, curCount_);
    memOut.putFloat(P_FLOAT, (float) 1.0);
    memOut.putLong(THETA_LONG, thetaLong_);
    
    //data
    if (curCount_ > 0) {
      MemoryUtil.copy(mem_, preBytes, memOut, preBytes, dataBytes);
    }
    return byteArrOut;
  }
  
  @Override
  public void reset() {
    lgArrLongs_ = 0;
    mem_.putByte(LG_ARR_LONGS_BYTE, (byte) (lgArrLongs_));
    curCount_ = -1; //Universal Set is true
    mem_.putInt(RETAINED_ENTRIES_INT, -1);
    thetaLong_ = Long.MAX_VALUE;
    mem_.putLong(THETA_LONG, Long.MAX_VALUE);
    empty_ = false;
    mem_.clearBits(FLAGS_BYTE, (byte) EMPTY_FLAG_MASK);
    mem_.clear(CONST_PREAMBLE_LONGS << 3, 8 << lgArrLongs_);
  }
  
  @Override
  public Family getFamily() {
    return Family.INTERSECTION;
  }
  
  private void performIntersect(Sketch sketchIn) {
    // HT and input data are nonzero, match against HT
    assert ((curCount_ > 0) && (!empty_));
    long[] cacheIn = sketchIn.getCache();
    int htLen = 1 << lgArrLongs_;
    long[] hashTable = new long[htLen];
    mem_.getLongArray(CONST_PREAMBLE_LONGS << 3, hashTable, 0, htLen);
    int arrLongsIn = cacheIn.length;
    //allocate space for matching
    long[] matchSet = new long[ min(curCount_, sketchIn.getRetainedEntries(true)) ];

    int matchSetCount = 0;
    if (sketchIn.isOrdered()) {
      //ordered compact, which enables early stop
      for (int i = 0; i < arrLongsIn; i++ ) {
        long hashIn = cacheIn[i];
        if (hashIn <= 0L) continue;
        if (hashIn >= thetaLong_) {
          break; //early stop assumes that hashes in input sketch are ordered!
        }
        int foundIdx = HashOperations.hashSearch(hashTable, lgArrLongs_, hashIn);
        if (foundIdx == -1) continue;
        matchSet[matchSetCount++] = hashIn;
      }

    } 
    else {
      //either unordered compact or hash table
      for (int i = 0; i < arrLongsIn; i++ ) {
        long hashIn = cacheIn[i];
        if ((hashIn <= 0L) || (hashIn >= thetaLong_)) continue;
        int foundIdx = HashOperations.hashSearch(hashTable, lgArrLongs_, hashIn);
        if (foundIdx == -1) continue;
        matchSet[matchSetCount++] = hashIn;
      }
    }
    //reduce effective array size to minimum
    lgArrLongs_ = setLgArrLongs(computeMinLgArrLongsFromCount(curCount_));
    curCount_ = setCurCount(matchSetCount);
    mem_.fill(CONST_PREAMBLE_LONGS << 3, 8 << lgArrLongs_, (byte) 0); //clear for rebuild
    //move matchSet to hash table
    moveDataToHT(matchSet, matchSetCount);
  }
  
  private void moveDataToHT(long[] arr, int count) {
    int arrLongsIn = arr.length;
    int tmpCnt = 0;
    int preBytes = CONST_PREAMBLE_LONGS << 3;
    for (int i = 0; i < arrLongsIn; i++ ) {
      long hashIn = arr[i];
      if (HashOperations.continueCondition(thetaLong_, hashIn)) continue;
      // opportunity to use faster unconditional insert
      tmpCnt += HashOperations.hashSearchOrInsert(mem_, lgArrLongs_, hashIn, preBytes) < 0 ? 1 : 0;
    }
    if (tmpCnt != count) {
      throw new IllegalArgumentException("Count Check Exception: got: "+tmpCnt+", expected: "+count);
    }
  }
  
  //special handlers
  
  private static final int checkMaxLgArrLongs(Memory dstMem) {
    int preBytes = CONST_PREAMBLE_LONGS << 3;
    long cap = dstMem.getCapacity();
    int maxLgArrLongs = Integer.numberOfTrailingZeros(floorPowerOf2((int)(cap - preBytes)) >>> 3);
    if (maxLgArrLongs < MIN_LG_ARR_LONGS) {
      throw new IllegalArgumentException(
        "dstMem not large enough for minimum sized hash table: "+ cap);
    }
    return maxLgArrLongs;
  }
  
  private final boolean setEmpty(boolean empty) {
    if (empty) {
      mem_.setBits(FLAGS_BYTE, (byte) EMPTY_FLAG_MASK);
    } 
    else {
      mem_.clearBits(FLAGS_BYTE, (byte)EMPTY_FLAG_MASK);
    }
    return empty;
  }
  
  private final int setLgArrLongs(int lgArrLongs) {
    mem_.putByte(LG_ARR_LONGS_BYTE, (byte) lgArrLongs);
    return lgArrLongs;
  }
  
  private final long setThetaLong(long thetaLong) {
    mem_.putLong(THETA_LONG, thetaLong);
    return thetaLong;
  }
  
  private final long minThetaLong(long skThetaLong) {
    if (skThetaLong < thetaLong_) {
      mem_.putLong(THETA_LONG, skThetaLong);
      return skThetaLong;
    }
    return thetaLong_;
  }
  
  private final int setCurCount(int curCount) {
    mem_.putInt(RETAINED_ENTRIES_INT, curCount);
    return curCount;
  }
}
