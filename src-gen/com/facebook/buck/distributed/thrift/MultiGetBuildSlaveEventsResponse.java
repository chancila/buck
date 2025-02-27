/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.12.0)")
public class MultiGetBuildSlaveEventsResponse implements org.apache.thrift.TBase<MultiGetBuildSlaveEventsResponse, MultiGetBuildSlaveEventsResponse._Fields>, java.io.Serializable, Cloneable, Comparable<MultiGetBuildSlaveEventsResponse> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("MultiGetBuildSlaveEventsResponse");

  private static final org.apache.thrift.protocol.TField RESPONSES_FIELD_DESC = new org.apache.thrift.protocol.TField("responses", org.apache.thrift.protocol.TType.LIST, (short)1);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new MultiGetBuildSlaveEventsResponseStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new MultiGetBuildSlaveEventsResponseTupleSchemeFactory();

  public @org.apache.thrift.annotation.Nullable java.util.List<BuildSlaveEventsRange> responses; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    RESPONSES((short)1, "responses");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // RESPONSES
          return RESPONSES;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final _Fields optionals[] = {_Fields.RESPONSES};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.RESPONSES, new org.apache.thrift.meta_data.FieldMetaData("responses", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, BuildSlaveEventsRange.class))));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(MultiGetBuildSlaveEventsResponse.class, metaDataMap);
  }

  public MultiGetBuildSlaveEventsResponse() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public MultiGetBuildSlaveEventsResponse(MultiGetBuildSlaveEventsResponse other) {
    if (other.isSetResponses()) {
      java.util.List<BuildSlaveEventsRange> __this__responses = new java.util.ArrayList<BuildSlaveEventsRange>(other.responses.size());
      for (BuildSlaveEventsRange other_element : other.responses) {
        __this__responses.add(new BuildSlaveEventsRange(other_element));
      }
      this.responses = __this__responses;
    }
  }

  public MultiGetBuildSlaveEventsResponse deepCopy() {
    return new MultiGetBuildSlaveEventsResponse(this);
  }

  @Override
  public void clear() {
    this.responses = null;
  }

  public int getResponsesSize() {
    return (this.responses == null) ? 0 : this.responses.size();
  }

  @org.apache.thrift.annotation.Nullable
  public java.util.Iterator<BuildSlaveEventsRange> getResponsesIterator() {
    return (this.responses == null) ? null : this.responses.iterator();
  }

  public void addToResponses(BuildSlaveEventsRange elem) {
    if (this.responses == null) {
      this.responses = new java.util.ArrayList<BuildSlaveEventsRange>();
    }
    this.responses.add(elem);
  }

  @org.apache.thrift.annotation.Nullable
  public java.util.List<BuildSlaveEventsRange> getResponses() {
    return this.responses;
  }

  public MultiGetBuildSlaveEventsResponse setResponses(@org.apache.thrift.annotation.Nullable java.util.List<BuildSlaveEventsRange> responses) {
    this.responses = responses;
    return this;
  }

  public void unsetResponses() {
    this.responses = null;
  }

  /** Returns true if field responses is set (has been assigned a value) and false otherwise */
  public boolean isSetResponses() {
    return this.responses != null;
  }

  public void setResponsesIsSet(boolean value) {
    if (!value) {
      this.responses = null;
    }
  }

  public void setFieldValue(_Fields field, @org.apache.thrift.annotation.Nullable java.lang.Object value) {
    switch (field) {
    case RESPONSES:
      if (value == null) {
        unsetResponses();
      } else {
        setResponses((java.util.List<BuildSlaveEventsRange>)value);
      }
      break;

    }
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case RESPONSES:
      return getResponses();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case RESPONSES:
      return isSetResponses();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof MultiGetBuildSlaveEventsResponse)
      return this.equals((MultiGetBuildSlaveEventsResponse)that);
    return false;
  }

  public boolean equals(MultiGetBuildSlaveEventsResponse that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_responses = true && this.isSetResponses();
    boolean that_present_responses = true && that.isSetResponses();
    if (this_present_responses || that_present_responses) {
      if (!(this_present_responses && that_present_responses))
        return false;
      if (!this.responses.equals(that.responses))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetResponses()) ? 131071 : 524287);
    if (isSetResponses())
      hashCode = hashCode * 8191 + responses.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(MultiGetBuildSlaveEventsResponse other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetResponses()).compareTo(other.isSetResponses());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetResponses()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.responses, other.responses);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  @org.apache.thrift.annotation.Nullable
  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    scheme(iprot).read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public java.lang.String toString() {
    java.lang.StringBuilder sb = new java.lang.StringBuilder("MultiGetBuildSlaveEventsResponse(");
    boolean first = true;

    if (isSetResponses()) {
      sb.append("responses:");
      if (this.responses == null) {
        sb.append("null");
      } else {
        sb.append(this.responses);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class MultiGetBuildSlaveEventsResponseStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public MultiGetBuildSlaveEventsResponseStandardScheme getScheme() {
      return new MultiGetBuildSlaveEventsResponseStandardScheme();
    }
  }

  private static class MultiGetBuildSlaveEventsResponseStandardScheme extends org.apache.thrift.scheme.StandardScheme<MultiGetBuildSlaveEventsResponse> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, MultiGetBuildSlaveEventsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // RESPONSES
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list168 = iprot.readListBegin();
                struct.responses = new java.util.ArrayList<BuildSlaveEventsRange>(_list168.size);
                @org.apache.thrift.annotation.Nullable BuildSlaveEventsRange _elem169;
                for (int _i170 = 0; _i170 < _list168.size; ++_i170)
                {
                  _elem169 = new BuildSlaveEventsRange();
                  _elem169.read(iprot);
                  struct.responses.add(_elem169);
                }
                iprot.readListEnd();
              }
              struct.setResponsesIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, MultiGetBuildSlaveEventsResponse struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.responses != null) {
        if (struct.isSetResponses()) {
          oprot.writeFieldBegin(RESPONSES_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, struct.responses.size()));
            for (BuildSlaveEventsRange _iter171 : struct.responses)
            {
              _iter171.write(oprot);
            }
            oprot.writeListEnd();
          }
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class MultiGetBuildSlaveEventsResponseTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public MultiGetBuildSlaveEventsResponseTupleScheme getScheme() {
      return new MultiGetBuildSlaveEventsResponseTupleScheme();
    }
  }

  private static class MultiGetBuildSlaveEventsResponseTupleScheme extends org.apache.thrift.scheme.TupleScheme<MultiGetBuildSlaveEventsResponse> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, MultiGetBuildSlaveEventsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetResponses()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetResponses()) {
        {
          oprot.writeI32(struct.responses.size());
          for (BuildSlaveEventsRange _iter172 : struct.responses)
          {
            _iter172.write(oprot);
          }
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, MultiGetBuildSlaveEventsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        {
          org.apache.thrift.protocol.TList _list173 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, iprot.readI32());
          struct.responses = new java.util.ArrayList<BuildSlaveEventsRange>(_list173.size);
          @org.apache.thrift.annotation.Nullable BuildSlaveEventsRange _elem174;
          for (int _i175 = 0; _i175 < _list173.size; ++_i175)
          {
            _elem174 = new BuildSlaveEventsRange();
            _elem174.read(iprot);
            struct.responses.add(_elem174);
          }
        }
        struct.setResponsesIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

