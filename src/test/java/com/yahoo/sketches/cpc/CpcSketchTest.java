/*
 * Copyright 2018, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.cpc;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.PrintStream;

import org.testng.annotations.Test;

/**
 * @author Lee Rhodes
 */
public class CpcSketchTest {
  static PrintStream ps = System.out;

  @Test
  public void checkUpdatesEstimate() {
    CpcSketch sk = new CpcSketch(10, 0);
    println(sk.toString(true));
    assertEquals(sk.getFormat(), Format.EMPTY_HIP);
    sk.update(1L);
    sk.update(2.0);
    sk.update("3");
    sk.update(new byte[] { 4 });
    sk.update(new char[] { 5 });
    sk.update(new int[] { 6 });
    sk.update(new long[] { 7 });
    double est = sk.getEstimate();
    double lb = sk.getLowerBound(2);
    double ub = sk.getUpperBound(2);
    assertTrue(lb >= 0);
    assertTrue(lb <= est);
    assertTrue(est <= ub);
    assertEquals(sk.getFlavor(), Flavor.SPARSE);
    assertEquals(sk.getFormat(), Format.SPARSE_HYBRID_HIP);
    println(sk.toString());
    println(sk.toString(true));
  }

  @Test
  public void checkEstimatesWithMerge() {
    int lgK = 4;
    CpcSketch sk1 = new CpcSketch(lgK);
    CpcSketch sk2 = new CpcSketch(lgK);
    int n = 1 << lgK;
    for (int i = 0; i < n; i++ ) {
      sk1.update(i);
      sk2.update(i + n);
    }
    CpcUnion union = new CpcUnion(lgK);
    union.update(sk1);
    union.update(sk2);
    CpcSketch result = union.getResult();
    double est = result.getEstimate();
    double lb = result.getLowerBound(2);
    double ub = result.getUpperBound(2);
    assertTrue(lb >= 0);
    assertTrue(lb <= est);
    assertTrue(est <= ub);
    println(result.toString(true));
  }

  @Test
  public void checkCornerCaseUpdates() {
    int lgK = 4;
    CpcSketch sk = new CpcSketch(lgK);
    sk.update(0.0);
    sk.update(-0.0);
    int est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);
    String s = null;
    sk.update(s);
    s = "";
    sk.update(s);
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);

    byte[] barr = null;
    sk.update(barr);
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);
    barr = new byte[0];
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);

    char[] carr = null;
    sk.update(carr);
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);
    carr = new char[0];
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);

    int[] iarr = null;
    sk.update(iarr);
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);
    iarr = new int[0];
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);

    long[] larr = null;
    sk.update(larr);
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);
    larr = new long[0];
    est = (int) Math.round(sk.getEstimate());
    assertEquals(est, 1);
  }

  @Test
  public void checkCornerHashUpdates() {
    CpcSketch sk = new CpcSketch(26);
    long hash1 = 0;
    long hash0 = -1L;
    sk.hashUpdate(hash0, hash1);
    PairTable table = sk.pairTable;
    println(PairTable.toString(table, true));
  }

  @SuppressWarnings("unused")
  @Test
  public void checkCopyWithWindow() {
    int lgK = 4;
    CpcSketch sk = new CpcSketch(lgK);
    CpcSketch sk2 = sk.copy();
    for (int i = 0; i < (1 << lgK); i++) { //pinned
      sk.update(i);
    }
    sk2 = sk.copy();
    long[] bitMatrix = CpcUtil.bitMatrixOfSketch(sk);
    CpcSketch.refreshKXP(sk, bitMatrix);
  }


  /**
   * @param s the string to print
   */
  private static void println(String s) {
    //ps.println(s);  //disable here
  }

}