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

package org.apache.carbondata.core.scan.result.vector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.scan.scanner.LazyPageLoader;

public interface CarbonColumnVector {

  void putBoolean(int rowId, boolean value);

  void putFloat(int rowId, float value);

  void putFloats(int rowId, int count, float[] src, int srcIndex);

  void putShort(int rowId, short value);

  void putShorts(int rowId, int count, short value);

  void putShorts(int rowId, int count, short[] src, int srcIndex);

  void putInt(int rowId, int value);

  void putInts(int rowId, int count, int value);

  void putInts(int rowId, int count, int[] src, int srcIndex);

  void putLong(int rowId, long value);

  void putLongs(int rowId, int count, long value);

  void putLongs(int rowId, int count, long[] src, int srcIndex);

  void putDecimal(int rowId, BigDecimal value, int precision);

  void putDecimals(int rowId, int count, BigDecimal value, int precision);

  void putDouble(int rowId, double value);

  void putDoubles(int rowId, int count, double value);

  void putDoubles(int rowId, int count, double[] src, int srcIndex);

  void putByteArray(int rowId, byte[] value);

  void putByteArray(int rowId, int offset, int length, byte[] value);

  void putArray(int rowId, int offset, int length);

  void putAllByteArray(byte[] data, int offset, int length);

  void putByte(int rowId, byte value);

  void putByteArray(int rowId, int count, byte[] value);

  void putBytes(int rowId, int count, byte[] src, int srcIndex);

  void putNull(int rowId);

  void putNulls(int rowId, int count);

  void putNotNull(int rowId);

  void putNotNull(int rowId, int count);

  boolean isNull(int rowId);

  void putObject(int rowId, Object obj);

  Object getData(int rowId);

  void reset();

  DataType getType();

  /**
   * Method to be used for getting the restructured data type. This method will used for
   * retrieving the data after change in data type restructure operation
   *
   * @return
   */
  DataType getBlockDataType();

  /**
   * Method to be used for setting the restructured data type. This method will used for
   * retrieving the data after change in data type restructure operation
   */
  void setBlockDataType(DataType blockDataType);

  void setFilteredRowsExist(boolean filteredRowsExist);

  void setDictionary(CarbonDictionary dictionary);

  boolean hasDictionary();

  CarbonColumnVector getDictionaryVector();

  void setLazyPage(LazyPageLoader lazyPage);

  void setCarbonDataFileWrittenVersion(String carbonDataFileWrittenVersion);

  // Added default implementation for interface,
  // to avoid implementing presto required functions for spark or core module.
  default List<CarbonColumnVector> getChildrenVector() {
    return new ArrayList<>(0);
  }

  // Added default implementation for interface,
  // to avoid implementing presto required functions for spark or core module.
  default void putComplexObject(List<Integer> offsetVector) {
  }

  // Added default implementation for interface,
  // to avoid implementing presto required functions for spark or core module.
  default CarbonColumnVector getColumnVector() {
    return null;
  }

  // Added default implementation for interface,
  // to avoid implementing presto required functions for spark or core module.
  default void setPositionCount(int positionCount) {
    throw new UnsupportedOperationException(
        "Method can only be called using instance of SliceStreamReader");
  }

  // Added default implementation for interface,
  // to avoid implementing presto required functions for spark or core module.
  default void setIsLocalDictEnabledForComplextype(boolean value) {
    throw new UnsupportedOperationException(
        "Method can only be called using instance of SliceStreamReader");
  }
}
