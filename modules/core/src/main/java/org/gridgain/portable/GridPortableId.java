/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.portable;

import java.lang.annotation.*;

/**
 * ID annotation for portable objects. Allows to provide
 * custom ID for a type or a field.
 * <p>
 * If {@code 0} is returned, hash code of class simple name
 * or field name will be used.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface GridPortableId {
    /**
     * Gets custom ID.
     *
     * @return Custom ID.
     */
    public int id();
}