/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;
import java.io.Serializable;

/**
 * One or more variables that together maintain an initially zero
 * {@code long} sum.  When updates (method {@link #add}) are contended
 * across threads, the set of variables may grow dynamically to reduce
 * contention. Method {@link #sum} (or, equivalently, {@link
 * #longValue}) returns the current total combined across the
 * variables maintaining the sum.
 *
 * <p>This class is usually preferable to {@link AtomicLong} when
 * multiple threads update a common sum that is used for purposes such
 * as collecting statistics, not for fine-grained synchronization
 * control.  Under low update contention, the two classes have similar
 * characteristics. But under high contention, expected throughput of
 * this class is significantly higher, at the expense of higher space
 * consumption.
 *
 * <p>LongAdders can be used with a {@link
 * java.util.concurrent.ConcurrentHashMap} to maintain a scalable
 * frequency map (a form of histogram or multiset). For example, to
 * add a count to a {@code ConcurrentHashMap<String,LongAdder> freqs},
 * initializing if not already present, you can use {@code
 * freqs.computeIfAbsent(k -> new LongAdder()).increment();}
 *
 * <p>This class extends {@link Number}, but does <em>not</em> define
 * methods such as {@code equals}, {@code hashCode} and {@code
 * compareTo} because instances are expected to be mutated, and so are
 * not useful as collection keys.
 *
 * @since 1.8
 * @author Doug Lea
 */

/**
 * 使用一个或多个变量来维护一个初始值为0的总和。当多个线程指定add方法遇到冲突时，
 * 这一系列的变量就会动态增长来减小冲突。sum()跟longValue()两个方法会返回当前
 * 由着一系列变量组成的总和值。
 *
 * 该类在多线程修改数值，比如用于统计的场景中（但不是细粒度的同步控制），比AtomicLong更推荐。
 * 当并发冲突小时，它跟AtomicLong性能类似，但当冲突很大时，它的性能更好，但也会消耗更多空间。
 *
 * 该类可能结合ConcurrentHashMap一起使用来达到统计效果，比如为ConcurrentHashMap<String,LongAdder> freqs添加值时
 * 如果对应Key还不存在，可以使用freqs.computeIfAbsent(k -> new LongAdder()).increment();
 *
 * 该类继承了Number,但由于该类的实例一般都是可变的，不能用作集合的key，因此没有重写equals,hashCode,compareTo方法。
 *
 */
public class LongAdder extends Striped64 implements Serializable {
    private static final long serialVersionUID = 7249069246863182397L;

    /**
     * Creates a new adder with initial sum of zero.
     */
    public LongAdder() {
    }

    /**
     * Adds the given value.
     *
     * @param x the value to add
     */
    public void add(long x) {
        //as 表示cells引用
        //b 表示获取的base值
        //v 表示期望值
        //m 表cells数组的长度
        //a 表示当前线程命中的cell单元格
        Cell[] as; long b, v; int m; Cell a;
        //条件一：true -> 表示cells已经初始化过了 当前线程应该将数据写入到对应的cell中
        //       false -> 表示cells未初始化 当前所有线程应该将数据写入到base中
        //条件二： true -> 表示当前线程cas替换成功
        //        false -> 表示发生竞争 可能需要重试或者扩容
        if ((as = cells) != null || !casBase(b = base, b + x)) {
            //什么时候会进来
            //1.true -> 表示cells已经初始化过了 当前线程应该将数据写入到对应的cell中
            //2.false -> 表示发生竞争 可能需要重试或者扩容

            //true 未竞争 false发生竞争
            boolean uncontended = true;
            //条件一： true 说明cells未初始化 也就是多线程写base发生竞争
            //        false 说明cells已经初始化 当前线程应该是找自己的cell 写值
            //条件二： getProbe()获取当前线程的hash值 m表示cells长度 - 1 cells长度为2的次方数
            //        true 说明当前线程对应下标的cell为空 需要创建 longAccumulate支持
            //        false 说明当前线程对应的cel不为空 说明下一步应该将x值添加到cell中
            //条件三： true 表示cas失败 意味着当前线程对应的cell有竞争
            //        false 表示cas成功
            if (as == null || (m = as.length - 1) < 0 ||
                (a = as[getProbe() & m]) == null ||
                !(uncontended = a.cas(v = a.value, v + x)))
                //那些情况会调用
                //1.true -> 说明cells未初始化 也就是多线程写base发生竞争[重试|初始化cells]
                //2.true -> 说明当前线程对应下标的cell为空 需要创建 longAccumulate支持
                //3.true -> cas失败 意味着当前线程对应的cell有竞争[重试|扩容]
                longAccumulate(x, null, uncontended);
        }
    }

    /**
     * Equivalent to {@code add(1)}.
     */
    public void increment() {
        add(1L);
    }

    /**
     * Equivalent to {@code add(-1)}.
     */
    public void decrement() {
        add(-1L);
    }

    /**
     * Returns the current sum.  The returned value is <em>NOT</em> an
     * atomic snapshot; invocation in the absence of concurrent
     * updates returns an accurate result, but concurrent updates that
     * occur while the sum is being calculated might not be
     * incorporated.
     *
     * @return the sum
     */

    /**
     * 返回当前的总和，返回值不是一个原子快照，当该方法在非并发修改的场景下执行时
     * 返回的是一个精确值，但如果在计算总和的过程中有并发修改，那么新修改的值可能
     * 不会被计算进去。
     *
     * @return
     */
    public long sum() {
        Cell[] as = cells; Cell a;
        long sum = base;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    /**
     * Resets variables maintaining the sum to zero.  This method may
     * be a useful alternative to creating a new adder, but is only
     * effective if there are no concurrent updates.  Because this
     * method is intrinsically racy, it should only be used when it is
     * known that no threads are concurrently updating.
     */
    public void reset() {
        Cell[] as = cells; Cell a;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    a.value = 0L;
            }
        }
    }

    /**
     * Equivalent in effect to {@link #sum} followed by {@link
     * #reset}. This method may apply for example during quiescent
     * points between multithreaded computations.  If there are
     * updates concurrent with this method, the returned value is
     * <em>not</em> guaranteed to be the final value occurring before
     * the reset.
     *
     * @return the sum
     */
    public long sumThenReset() {
        Cell[] as = cells; Cell a;
        long sum = base;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null) {
                    sum += a.value;
                    a.value = 0L;
                }
            }
        }
        return sum;
    }

    /**
     * Returns the String representation of the {@link #sum}.
     * @return the String representation of the {@link #sum}
     */
    public String toString() {
        return Long.toString(sum());
    }

    /**
     * Equivalent to {@link #sum}.
     *
     * @return the sum
     */
    public long longValue() {
        return sum();
    }

    /**
     * Returns the {@link #sum} as an {@code int} after a narrowing
     * primitive conversion.
     */
    public int intValue() {
        return (int)sum();
    }

    /**
     * Returns the {@link #sum} as a {@code float}
     * after a widening primitive conversion.
     */
    public float floatValue() {
        return (float)sum();
    }

    /**
     * Returns the {@link #sum} as a {@code double} after a widening
     * primitive conversion.
     */
    public double doubleValue() {
        return (double)sum();
    }

    /**
     * Serialization proxy, used to avoid reference to the non-public
     * Striped64 superclass in serialized forms.
     * @serial include
     */
    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        /**
         * The current value returned by sum().
         * @serial
         */
        private final long value;

        SerializationProxy(LongAdder a) {
            value = a.sum();
        }

        /**
         * Return a {@code LongAdder} object with initial state
         * held by this proxy.
         *
         * @return a {@code LongAdder} object with initial state
         * held by this proxy.
         */
        private Object readResolve() {
            LongAdder a = new LongAdder();
            a.base = value;
            return a;
        }
    }

    /**
     * Returns a
     * <a href="../../../../serialized-form.html#java.util.concurrent.atomic.LongAdder.SerializationProxy">
     * SerializationProxy</a>
     * representing the state of this instance.
     *
     * @return a {@link SerializationProxy}
     * representing the state of this instance
     */
    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    /**
     * @param s the stream
     * @throws java.io.InvalidObjectException always
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }

}
