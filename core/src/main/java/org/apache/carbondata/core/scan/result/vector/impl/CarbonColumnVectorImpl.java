/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.core.scan.result.vector.impl;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.datatype.DecimalType;
import org.apache.carbondata.core.scan.result.vector.CarbonColumnVector;
import org.apache.carbondata.core.scan.result.vector.CarbonDictionary;
import org.apache.carbondata.core.scan.scanner.LazyPageLoader;

public class CarbonColumnVectorImpl implements CarbonColumnVector {

  private Object[] data;

  private int[] ints;

  private long[] longs;

  private BigDecimal[] decimals;

  private byte[] byteArr;

  private byte[][] bytes;

  private float[] floats;

  private double[] doubles;

  private short[] shorts;

  protected BitSet nullBytes;

  private DataType dataType;

  private DataType blockDataType;

  private int[] lengths;

  private int[] offsets;

  private int batchSize;

  /**
   * True if there is at least one NULL byte set. This is an optimization for the writer, to skip
   * having to clear NULL bits.
   */
  protected boolean anyNullsSet;

  private CarbonDictionary carbonDictionary;

  private CarbonColumnVector dictionaryVector;

  private List<CarbonColumnVector> childrenVector;

  private LazyPageLoader lazyPage;

  private boolean loaded;

  private List<Integer> numberOfChildElementsInEachRow;

  private CarbonDictionary localDictionary;

  public CarbonColumnVectorImpl(int batchSize, DataType dataType) {
    this.batchSize = batchSize;
    nullBytes = new BitSet(batchSize);
    this.dataType = dataType;
    if (dataType == DataTypes.BOOLEAN || dataType == DataTypes.BYTE) {
      byteArr = new byte[batchSize];
    } else if (dataType == DataTypes.SHORT) {
      shorts = new short[batchSize];
    } else if (dataType == DataTypes.INT || dataType == DataTypes.DATE) {
      ints = new int[batchSize];
    } else if (dataType == DataTypes.LONG || dataType == DataTypes.TIMESTAMP) {
      longs = new long[batchSize];
    } else if (dataType == DataTypes.FLOAT) {
      floats = new float[batchSize];
    } else if (dataType == DataTypes.DOUBLE) {
      doubles = new double[batchSize];
    } else if (dataType instanceof DecimalType) {
      decimals = new BigDecimal[batchSize];
    } else if (dataType == DataTypes.STRING || dataType == DataTypes.BYTE_ARRAY
        || dataType == DataTypes.VARCHAR || dataType == DataTypes.BINARY) {
      dictionaryVector = new CarbonColumnVectorImpl(batchSize, DataTypes.INT);
      bytes = new byte[batchSize][];
    } else {
      data = new Object[batchSize];
    }

  }

  @Override
  public List<CarbonColumnVector> getChildrenVector() {
    return childrenVector;
  }

  public void setChildrenVector(List<CarbonColumnVector> childrenVector) {
    this.childrenVector = childrenVector;
  }

  public List<Integer> getNumberOfChildrenElementsInEachRow() {
    return numberOfChildElementsInEachRow;
  }

  public void setNumberOfChildElementsInEachRow(List<Integer> numberOfChildElementsInEachRow) {
    this.numberOfChildElementsInEachRow = numberOfChildElementsInEachRow;
  }

  public void setNumberOfElementsInEachRowForArray(byte[] parentPageData, int pageSize) {
    // for complex array type, go through parent page to get the child information
    ByteBuffer childInfoBuffer = ByteBuffer.wrap(parentPageData);
    List<Integer> numberOfArrayElementsInEachRow = new ArrayList<>();
    // Parent page array data looks like
    // number of children in each row [4 byte], Offset [4 byte],
    // number of children in each row [4 byte], Offset [4 byte]...
    while (pageSize != numberOfArrayElementsInEachRow.size()) {
      // get the number of array elements in current row
      numberOfArrayElementsInEachRow.add(childInfoBuffer.getInt());
      // skip offset
      childInfoBuffer.getInt();
    }
    setNumberOfChildElementsInEachRow(numberOfArrayElementsInEachRow);
  }

  public void setNumberOfElementsInEachRowForStruct(byte[] parentPageData, int pageSize) {
    // for complex struct type, go through parent page to get the child information
    ByteBuffer childInfoBuffer = ByteBuffer.wrap(parentPageData);
    List<Integer> numberOfStructElementsInEachRow = new ArrayList<>();
    // Parent page struct data looks like
    // number of children in each row [2 byte], number of children in each row [2 byte],
    // number of children in each row [2 byte], number of children in each row [2 byte]...
    while (pageSize != numberOfStructElementsInEachRow.size()) {
      int elements = childInfoBuffer.getShort();
      numberOfStructElementsInEachRow.add(elements);
    }
    setNumberOfChildElementsInEachRow(numberOfStructElementsInEachRow);
  }

  public CarbonDictionary getLocalDictionary() {
    return localDictionary;
  }

  public void setLocalDictionary(CarbonDictionary localDictionary) {
    this.localDictionary = localDictionary;
  }

  @Override
  public void putBoolean(int rowId, boolean value) {
    byteArr[rowId] =  (byte)((value) ? 1 : 0);
  }

  @Override
  public void putFloat(int rowId, float value) {
    floats[rowId] = value;
  }

  @Override
  public void putShort(int rowId, short value) {
    shorts[rowId] = value;
  }

  @Override
  public void putShorts(int rowId, int count, short value) {
    for (int i = 0; i < count; ++i) {
      shorts[i + rowId] = value;
    }
  }

  @Override
  public void putInt(int rowId, int value) {
    ints[rowId] = value;
  }

  @Override
  public void putInts(int rowId, int count, int value) {
    for (int i = 0; i < count; ++i) {
      ints[i + rowId] = value;
    }
  }

  @Override
  public void putLong(int rowId, long value) {
    longs[rowId] = value;
  }

  @Override
  public void putLongs(int rowId, int count, long value) {
    for (int i = 0; i < count; ++i) {
      longs[i + rowId] = value;
    }
  }

  @Override
  public void putDecimal(int rowId, BigDecimal  value, int precision) {
    decimals[rowId] = value;
  }

  @Override
  public void putDecimals(int rowId, int count, BigDecimal value, int precision) {
    for (int i = 0; i < count; ++i) {
      decimals[i + rowId] = value;
    }
  }

  @Override
  public void putDouble(int rowId, double value) {
    doubles[rowId] = value;
  }

  @Override
  public void putDoubles(int rowId, int count, double value) {
    for (int i = 0; i < count; ++i) {
      doubles[i + rowId] = value;
    }
  }

  @Override
  public void putByteArray(int rowId, byte[] value) {
    bytes[rowId] = value;
  }

  @Override
  public void putByte(int rowId, byte value) {
    byteArr[rowId] = value;
  }

  @Override
  public void putByteArray(int rowId, int count, byte[] value) {
    for (int i = 0; i < count; ++i) {
      bytes[i + rowId] = value;
    }
  }

  @Override
  public void putByteArray(int rowId, int offset, int length, byte[] value) {
    bytes[rowId] = new byte[length];
    System.arraycopy(value, offset, bytes[rowId], 0, length);
  }

  @Override
  public void putNull(int rowId) {
    nullBytes.set(rowId);
    anyNullsSet = true;
  }

  @Override
  public void putNulls(int rowId, int count) {
    for (int i = 0; i < count; ++i) {
      nullBytes.set(rowId + i);
    }
    anyNullsSet = true;
  }

  @Override
  public void putNotNull(int rowId) {

  }

  @Override
  public void putNotNull(int rowId, int count) {

  }

  public boolean isNullAt(int rowId) {
    return nullBytes.get(rowId);
  }

  @Override
  public boolean isNull(int rowId) {
    return nullBytes.get(rowId);
  }

  @Override
  public void putObject(int rowId, Object obj) {
    data[rowId] = obj;
  }

  @Override
  public Object getData(int rowId) {
    if (!loaded) {
      loadPage();
    }
    if (nullBytes.get(rowId)) {
      return null;
    }
    if (dataType == DataTypes.BOOLEAN || dataType == DataTypes.BYTE) {
      return  byteArr[rowId];
    } else if (dataType == DataTypes.SHORT) {
      return shorts[rowId];
    } else if (dataType == DataTypes.INT || dataType == DataTypes.DATE) {
      return ints[rowId];
    } else if (dataType == DataTypes.LONG || dataType == DataTypes.TIMESTAMP) {
      return longs[rowId];
    } else if (dataType == DataTypes.FLOAT) {
      return floats[rowId];
    } else if (dataType == DataTypes.DOUBLE) {
      return doubles[rowId];
    } else if (dataType instanceof DecimalType) {
      return decimals[rowId];
    } else if (dataType == DataTypes.STRING || dataType == DataTypes.BYTE_ARRAY
        || dataType == DataTypes.VARCHAR || dataType == DataTypes.BINARY) {
      if (null != carbonDictionary) {
        int dictKey = (Integer) dictionaryVector.getData(rowId);
        return carbonDictionary.getDictionaryValue(dictKey);
      } else if (byteArr != null) {
        byte[] bytes = new byte[lengths[rowId]];
        System.arraycopy(byteArr, offsets[rowId], bytes, 0, bytes.length);
        return bytes;
      } else {
        return bytes[rowId];
      }
    } else {
      return data[rowId];
    }
  }

  public Object getDataArray() {
    if (!loaded) {
      loadPage();
    }
    if (dataType == DataTypes.BOOLEAN || dataType == DataTypes.BYTE) {
      return  byteArr;
    } else if (dataType == DataTypes.SHORT) {
      return shorts;
    } else if (dataType == DataTypes.INT) {
      return ints;
    } else if (dataType == DataTypes.LONG || dataType == DataTypes.TIMESTAMP) {
      return longs;
    } else if (dataType == DataTypes.FLOAT) {
      return floats;
    } else if (dataType == DataTypes.DOUBLE) {
      return doubles;
    } else if (dataType instanceof DecimalType) {
      return decimals;
    } else if (dataType == DataTypes.STRING || dataType == DataTypes.BYTE_ARRAY || dataType ==
        DataTypes.VARCHAR) {
      if (null != carbonDictionary) {
        return ints;
      }
      return bytes;
    } else {
      return data;
    }
  }

  @Override
  public void reset() {
    nullBytes.clear();
    if (dataType == DataTypes.BOOLEAN || dataType == DataTypes.BYTE) {
      Arrays.fill(byteArr, (byte) 0);
    } else if (dataType == DataTypes.SHORT) {
      Arrays.fill(shorts, (short) 0);
    } else if (dataType == DataTypes.INT || dataType == DataTypes.DATE) {
      Arrays.fill(ints, 0);
    } else if (dataType == DataTypes.LONG || dataType == DataTypes.TIMESTAMP) {
      Arrays.fill(longs, 0);
    } else if (dataType == DataTypes.FLOAT) {
      Arrays.fill(floats, 0);
    } else if (dataType == DataTypes.DOUBLE) {
      Arrays.fill(doubles, 0);
    } else if (dataType instanceof DecimalType) {
      Arrays.fill(decimals, null);
    } else if (dataType == DataTypes.STRING || dataType == DataTypes.BYTE_ARRAY
        || dataType == DataTypes.VARCHAR || dataType == DataTypes.BINARY) {
      Arrays.fill(bytes, null);
      this.dictionaryVector.reset();
    } else {
      Arrays.fill(data, null);
    }
    loaded = false;

  }

  @Override
  public DataType getType() {
    return dataType;
  }

  @Override
  public DataType getBlockDataType() {
    return blockDataType;
  }

  @Override
  public void setBlockDataType(DataType blockDataType) {
    this.blockDataType = blockDataType;
  }

  @Override
  public void setFilteredRowsExist(boolean filteredRowsExist) {

  }

  @Override
  public void setDictionary(CarbonDictionary dictionary) {
    this.carbonDictionary = dictionary;
  }

  @Override
  public boolean hasDictionary() {
    return null != this.carbonDictionary;
  }

  @Override
  public CarbonColumnVector getDictionaryVector() {
    return dictionaryVector;
  }

  /**
   * Returns true if any of the nulls indicator are set for this column. This can be used
   * as an optimization to prevent setting nulls.
   */
  public final boolean anyNullsSet() {
    return anyNullsSet;
  }

  @Override
  public void putFloats(int rowId, int count, float[] src, int srcIndex) {
    for (int i = srcIndex; i < count; i++) {
      floats[rowId++] = src[i];
    }
  }

  @Override
  public void putShorts(int rowId, int count, short[] src, int srcIndex) {
    for (int i = srcIndex; i < count; i++) {
      shorts[rowId++] = src[i];
    }
  }

  @Override
  public void putInts(int rowId, int count, int[] src, int srcIndex) {
    for (int i = srcIndex; i < count; i++) {
      ints[rowId++] = src[i];
    }
  }

  @Override
  public void putLongs(int rowId, int count, long[] src, int srcIndex) {
    for (int i = srcIndex; i < count; i++) {
      longs[rowId++] = src[i];
    }
  }

  @Override
  public void putDoubles(int rowId, int count, double[] src, int srcIndex) {
    for (int i = srcIndex; i < count; i++) {
      doubles[rowId++] = src[i];
    }
  }

  @Override
  public void putBytes(int rowId, int count, byte[] src, int srcIndex) {
    for (int i = srcIndex; i < count; i++) {
      byteArr[rowId++] = src[i];
    }
  }

  @Override
  public void setLazyPage(LazyPageLoader lazyPage) {
    this.lazyPage = lazyPage;
  }

  public void loadPage() {
    if (lazyPage != null) {
      lazyPage.loadPage();
    }
    loaded = true;
  }

  @Override
  public void putArray(int rowId, int offset, int length) {
    if (offsets == null) {
      offsets = new int[batchSize];
      lengths = new int[batchSize];
    }
    offsets[rowId] = offset;
    lengths[rowId] = length;
  }

  @Override
  public void setCarbonDataFileWrittenVersion(String carbonDataFileWrittenVersion) {
   // do nothing here
  }

  @Override
  public void putAllByteArray(byte[] data, int offset, int length) {
    byteArr = data;
  }

  public int[] getLengths() {
    return lengths;
  }

  public int getIntArraySize() {
    return ints.length;
  }

  public void increaseIntArraySize(int size) {
    this.ints = new int[size];
  }

  public int[] getOffsets() {
    return offsets;
  }
}
