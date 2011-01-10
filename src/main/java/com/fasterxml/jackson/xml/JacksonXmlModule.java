package com.fasterxml.jackson.xml;

import java.util.*;

import javax.xml.namespace.QName;

import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.map.introspect.AnnotatedMember;
import org.codehaus.jackson.map.introspect.BasicBeanDescription;
import org.codehaus.jackson.map.module.SimpleModule;
import org.codehaus.jackson.map.ser.BeanPropertyWriter;
import org.codehaus.jackson.map.ser.BeanSerializer;
import org.codehaus.jackson.map.ser.BeanSerializerModifier;
import org.codehaus.jackson.type.JavaType;

import com.fasterxml.jackson.xml.ser.XmlBeanPropertyWriter;
import com.fasterxml.jackson.xml.ser.XmlBeanSerializer;
import com.fasterxml.jackson.xml.util.XmlInfo;

/**
 * Module that implements most functionality needed to support producing and
 * consuming XML instead of JSON.
 */
public class JacksonXmlModule extends SimpleModule
{
    private final static AnnotationIntrospector XML_ANNOTATION_INTROSPECTOR = new JacksonXmlAnnotationIntrospector();

    // !!! TODO: how to externalize version?
    private final static Version VERSION = new Version(0, 1, 0, null);
    
    public JacksonXmlModule()
    {
        super("JackxonXmlModule", VERSION);
    }
    
    @Override
    public void setupModule(SetupContext context)
    {
        context.addBeanSerializerModifier(new MySerializerModifier());
        context.insertAnnotationIntrospector(XML_ANNOTATION_INTROSPECTOR);
    }

    /*
    /**********************************************************
    /* Helper methods
    /**********************************************************
     */
    
    /**
     * Helper method used for figuring out if given raw type is a collection ("indexed") type;
     * in which case a wrapper element is typically added.
     */
    private static boolean _isContainerType(JavaType type)
    {
        if (type.isContainerType()) {
            // Just one special case; byte[] will be serialized as base64-encoded String, not real array, so:
            if (type.getRawClass() == byte[].class) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static String findNamespaceAnnotation(AnnotationIntrospector ai, AnnotatedMember prop)
    {
        for (AnnotationIntrospector intr : ai.allIntrospectors()) {
            if (intr instanceof XmlAnnotationIntrospector) {
                String ns = ((XmlAnnotationIntrospector) intr).findNamespace(prop);
                if (ns != null) {
                    return ns;
                }
            }
        }
        return null;
    }

    private static Boolean findIsAttributeAnnotation(AnnotationIntrospector ai, AnnotatedMember prop)
    {
        for (AnnotationIntrospector intr : ai.allIntrospectors()) {
            if (intr instanceof XmlAnnotationIntrospector) {
                Boolean b = ((XmlAnnotationIntrospector) intr).isOutputAsAttribute(prop);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private static QName findWrapperName(AnnotationIntrospector ai, AnnotatedMember prop)
    {
        for (AnnotationIntrospector intr : ai.allIntrospectors()) {
            if (intr instanceof XmlAnnotationIntrospector) {
                QName n = ((XmlAnnotationIntrospector) intr).findWrapperElement(prop);
                if (n != null) {
                    return n;
                }
            }
        }
        return null;
    }
    
    /*
    /**********************************************************
    /* Helper classes
    /**********************************************************
     */

    /**
     * We need a {@link SerializerModifier} to replace default <code>BeanSerializer</code>
     * with XML-specific one; mostly to ensure that attribute properties are output
     * before element properties.
     */
    protected static class MySerializerModifier extends BeanSerializerModifier
    {
        /**
         * First thing to do is to find annotations regarding XML serialization,
         * and wrap collection serializers.
         */
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                BasicBeanDescription beanDesc, List<BeanPropertyWriter> beanProperties)
        {
            AnnotationIntrospector intr = config.getAnnotationIntrospector();
            for (int i = 0, len = beanProperties.size(); i < len; ++i) {
                BeanPropertyWriter bpw = beanProperties.get(i);
                final AnnotatedMember member = bpw.getMember();
                String ns = findNamespaceAnnotation(intr, member);
                Boolean isAttribute = findIsAttributeAnnotation(intr, member);
                bpw.setInternalSetting(XmlBeanSerializer.KEY_XML_INFO, new XmlInfo(isAttribute, ns));

                // Actually: if we have a Collection type, easiest place to add wrapping would be here...
                if (_isContainerType(bpw.getType())) {
                    String localName = null, wrapperNs = null;

                    QName wrappedName = new QName(ns, bpw.getName());
                    QName wrapperName = findWrapperName(intr, member);
                    if (wrapperName != null) {
                        localName = wrapperName.getLocalPart();
                        wrapperNs = wrapperName.getNamespaceURI();
                    }
                    /* Empty/missing localName means "use property name as wrapper"; later on
                     * should probably make missing (null) mean "don't add a wrapper"
                     */
                    if (localName == null || localName.length() == 0) {
                        wrapperName = wrappedName;
                    } else {
                        wrapperName = new QName((wrapperNs == null) ? "" : wrapperNs, localName);
                    }
                    beanProperties.set(i, new XmlBeanPropertyWriter(bpw, wrapperName, wrappedName));
                }
            }
            return beanProperties;
        }
        
        public JsonSerializer<?> modifySerializer(SerializationConfig config,
                BasicBeanDescription beanDesc, JsonSerializer<?> serializer)
        {
            /* First things first: we can only handle real BeanSerializers; question
             * is, what to do if it's not one: throw exception or bail out?
             * For now let's do latter.
             */
            if (!(serializer instanceof BeanSerializer)) {
                return serializer;
            }
            return new XmlBeanSerializer((BeanSerializer) serializer);
        }
    }
}