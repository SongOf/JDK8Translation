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
import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A package-local class holding common representation and mechanics
 * for classes supporting dynamic striping on 64bit values. The class
 * extends Number so that concrete subclasses must publicly do so.
 */
@SuppressWarnings("serial")
abstract class Striped64 extends Number {

    /**
     * 该类维护了一张可以原子更新变量,延迟初始化的数组,新增一个base字段。
     * 该数组的大小为2的n次方。数组的索引使用调用线程的处理过的hashcode。
     * 该类中的字段都是包范围的,子类可以直接调用。
     *
     * 该数组的元素类型为Cell,是一个使用@sun.misc.Contended注解填充避免缓存
     * 冲突,支持原子操作的变量(可以理解为填充过的AtomicLong,但只支持更新操作)。
     * 填充这种方式对于大部分原子类型变量来说是不友好的,因为他们是无规则的分部
     * 在内存中,因此互相之间并不会有什么影响。但是当我们把这些原子变量存在一个
     * 数组中时,如果不对他们进行填充操作,他们在内存中就会互相挨着,这会导致共享
     * 缓存行(cache line),对性能有巨大副作用。
     *
     * 由于Cells相对是比较大的数组,因此要等我们使用到的时候再进行初始化。如果没有
     * 冲突,所有的更新操作都是基于base字段实现。当第一次冲突发生(base的更新操作
     * 是基于CAS的),Cell数组进行初始化,大小为2。后续每次遇到冲突,只要当前数组的
     * 大小没有CPU数量,就会进行两倍容量扩容。数组中的元素在确实用到之前都是null。
     *
     * 该类中使用一个简化的自旋锁(通过cellsBusy字段实现)进行数组的初始化,扩容,
     * 以及数组元素的填充。这里并没有使用阻塞锁的必要。当自旋锁获取失败时,调用线程
     * 会尝试新的数组槽或者是更新base字段,不可否认这些重试机制进一步增加了冲突减少
     * 了局限性(这个词后续考虑怎么翻译更好),但仍然要比一次上下文切换表现好。
     *
     * 调用线程的probe字段是通过ThreadLocalRandom生成的hashcode。它们的初始值都是0,
     * 只有在数组的0位置上产生争用时才会生成一个有效的值,这个值一般来说是不重复的。
     * 争用跟数据槽位碰撞表明了更新时CAS失败。一旦发生冲突,如果数组元素小于上限且其他
     * 线程没有持有锁,数组就会进行两倍扩容。如果数组中某个槽跟锁都是可用的,就是初始化
     * 一个Cell元素。如果槽是可用,但CAS失败,就会进行二次哈希(Marsaglia XorShift算法)
     * 来给调用线程重新生成一个hashcode来寻找新的槽位。
     *
     * 之所以要给数组大小设置上限是因为当线程数大于CPU数时,假如每个线程都占用了CPU,
     * 存在一些完美的hash函数可以在没有冲突的前提下把线程映射到数组槽上,当数组容量
     * 达到上限时,我们通过将冲突线程随机改变散列码来进行新的映射,由于新的映射是随机的
     * 那么就只有CAS失败了才能知道发生冲突了,这样性能就会降低,而且由于线程不是一直
     * 在CPU上执行,因此这种情况可能不会发生。不过这种可见的冲突发生的概率是很低的。
     *
     * 当线程终止或者扩容的时候,会是一些Cell变得不可用。由于系统长期运行的话,这些
     * Cell可能被重新使用,因此我们没有对这些暂时无用的Cell进行检测跟删除。当然,
     * 如果系统只是短期运行,那不处理这些没用的Cell也没什么问题。
     *
     */

    /*
     * This class maintains a lazily-initialized table of atomically
     * updated variables, plus an extra "base" field. The table size
     * is a power of two. Indexing uses masked per-thread hash codes.
     * Nearly all declarations in this class are package-private,
     * accessed directly by subclasses.
     *
     * Table entries are of class Cell; a variant of AtomicLong padded
     * (via @sun.misc.Contended) to reduce cache contention. Padding
     * is overkill for most Atomics because they are usually
     * irregularly scattered in memory and thus don't interfere much
     * with each other. But Atomic objects residing in arrays will
     * tend to be placed adjacent to each other, and so will most
     * often share cache lines (with a huge negative performance
     * impact) without this precaution.
     *
     * In part because Cells are relatively large, we avoid creating
     * them until they are needed.  When there is no contention, all
     * updates are made to the base field.  Upon first contention (a
     * failed CAS on base update), the table is initialized to size 2.
     * The table size is doubled upon further contention until
     * reaching the nearest power of two greater than or equal to the
     * number of CPUS. Table slots remain empty (null) until they are
     * needed.
     *
     * A single spinlock ("cellsBusy") is used for initializing and
     * resizing the table, as well as populating slots with new Cells.
     * There is no need for a blocking lock; when the lock is not
     * available, threads try other slots (or the base).  During these
     * retries, there is increased contention and reduced locality,
     * which is still better than alternatives.
     *
     * The Thread probe fields maintained via ThreadLocalRandom serve
     * as per-thread hash codes. We let them remain uninitialized as
     * zero (if they come in this way) until they contend at slot
     * 0. They are then initialized to values that typically do not
     * often conflict with others.  Contention and/or table collisions
     * are indicated by failed CASes when performing an update
     * operation. Upon a collision, if the table size is less than
     * the capacity, it is doubled in size unless some other thread
     * holds the lock. If a hashed slot is empty, and lock is
     * available, a new Cell is created. Otherwise, if the slot
     * exists, a CAS is tried.  Retries proceed by "double hashing",
     * using a secondary hash (Marsaglia XorShift) to try to find a
     * free slot.
     *
     * The table size is capped because, when there are more threads
     * than CPUs, supposing that each thread were bound to a CPU,
     * there would exist a perfect hash function mapping threads to
     * slots that eliminates collisions. When we reach capacity, we
     * search for this mapping by randomly varying the hash codes of
     * colliding threads.  Because search is random, and collisions
     * only become known via CAS failures, convergence can be slow,
     * and because threads are typically not bound to CPUS forever,
     * may not occur at all. However, despite these limitations,
     * observed contention rates are typically low in these cases.
     *
     * It is possible for a Cell to become unused when threads that
     * once hashed to it terminate, as well as in the case where
     * doubling the table causes no thread to hash to it under
     * expanded mask.  We do not try to detect or remove such cells,
     * under the assumption that for long-running instances, observed
     * contention levels will recur, so the cells will eventually be
     * needed again; and for short-lived ones, it does not matter.
     */

    /**
     * Padded variant of AtomicLong supporting only raw accesses plus CAS.
     *
     * JVM intrinsics note: It would be possible to use a release-only
     * form of CAS here, if it were provided.
     */
    @sun.misc.Contended static final class Cell {
        volatile long value;
        Cell(long x) { value = x; }
        final boolean cas(long cmp, long val) {
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long valueOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                    (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Number of CPUS, to place bound on table size */
    //表示当前计算机cpu数量 用处？控制cells数组长度
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * Table of cells. When non-null, size is a power of 2.
     */
    transient volatile Cell[] cells;

    /**
     * Base value, used mainly when there is no contention, but also as
     * a fallback during table initialization races. Updated via CAS.
     * 没有发生过竞争时 数据会累加到case 或者 当cells扩容时 需要将数据写到base中
     */
    transient volatile long base;

    /**
     * Spinlock (locked via CAS) used when resizing and/or creating Cells.
     * 初始化cells或者扩容cells都需要获取锁 0表示无锁状态 1表示其他线程持有锁
     */
    transient volatile int cellsBusy;

    /**
     * Package-private default constructor
     */
    Striped64() {
    }

    /**
     * CASes the base field.
     */
    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    /**
     * CASes the cellsBusy field from 0 to 1 to acquire lock.
     * 通过cas获取锁
     */
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    /**
     * Returns the probe value for the current thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     * 获取当前线程的hash值
     */
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    /**
     * Pseudo-randomly advances and records the given probe value for the
     * given thread.
     * Duplicated from ThreadLocalRandom because of packaging restrictions.
     * 重置线程的hash值
     */
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x the value
     * @param fn the update function, or null for add (this convention
     * avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     */
    //1.true -> 说明cells未初始化 也就是多线程写base发生竞争[重试|初始化cells]
    //2.true -> 说明当前线程对应下标的cell为空 需要创建 longAccumulate支持
    //3.true -> cas失败 意味着当前线程对应的cell有竞争[重试|扩容]

    //x 增值
    //wasUncontended 是否竞争 只有cells初始化之后 并且当前线程竞争修改失败 才会是false
    final void longAccumulate(long x, LongBinaryOperator fn,
                              boolean wasUncontended) {
        //h 表示线程hash值
        int h;
        //条件成立 说明当前线程 还未分配hash值
        if ((h = getProbe()) == 0) {
            //给当前线程分配hash值
            ThreadLocalRandom.current(); // force initialization
            //取出当前线程的hash值 赋值给h
            h = getProbe();
            //为什么？因为默认情况下 当前线程肯定是写入到了cells[0]位置 不把它当做一次真正的竞争
            wasUncontended = true;
        }
        //表示扩容意向 false一定不为扩容 true可能会扩容
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            //as 表示cells引用
            //a 表示当前线程命中的cell
            //n 表示cells数组长度
            //v 表示期望值
            Cell[] as; Cell a; int n; long v;
            //CASE1: 表示cells已经初始化了 当前线程应该将数据写入到对应的cell中
            if ((as = cells) != null && (n = as.length) > 0) {
                //CASE1.1:true -> 表示当前线程对应的下标位置的cell为null 需要创建new Cell
                if ((a = as[(n - 1) & h]) == null) {
                    //true -> 表示当前锁未占用 false-> 表示当前锁被占用
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        //创建cell
                        Cell r = new Cell(x);   // Optimistically create
                        //条件一 true -> 表示当前锁未占用 false-> 表示当前锁被占用
                        //条件二 true -> 表示当前线程获取锁成功 false -> 当前线程获取锁失败
                        if (cellsBusy == 0 && casCellsBusy()) {
                            //是否创建成功 标记
                            boolean created = false;
                            try {               // Recheck under lock
                                //rs 表示当前cells引用
                                //m 表示cells长度
                                //j 表示当前线程命中的下标
                                Cell[] rs; int m, j;
                                //条件一 条件二恒成立
                                //rs[j = (m - 1) & h] == null 为了防止其它线程初始化过该位置 当前线程再次初始化该位置会导致数据丢失
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    //扩容意向 强制改为false
                    collide = false;
                }
                //CASE1.2:
                //只有cells初始化之后 并且当前线程竞争修改失败 wasUncontended才会是false
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                //CASE1.3:
                //当前线程rehash过hash值 然后新命中的cell不为空
                //true 写成功 退出循环
                //false 表示rehash之后命中的新的cell也有竞争 重试一次 再重试一次（外面一次 本方法两次 三次后会扩容cells数组）
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                                             fn.applyAsLong(v, x))))
                    break;
                //CASE1.4:
                //条件一 n >= NCPU true -> 扩容意向改为false 表示不扩容了 false -> 说明cells数组还可以扩容
                //条件二 cells != as true -> 表示其它线程已经扩容过了 当前线程rehash之后重试即可
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                //CASE1.5:
                //!collide = true 设置扩容意向为true 但不一定真的发生扩容
                else if (!collide)
                    collide = true;
                //CASE1.6:真正扩容的逻辑
                //条件一 cellsBusy == 0 true -> 表示当前无锁状态 当前线程可以去竞争这把锁
                //条件二 casCellsBusy() true -> 表示当前线程获取锁成功，可以执行扩容逻辑
                //                     false 表示其他线程正在扩容
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        //cells == as
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                //重置当前线程hash值
                h = advanceProbe(h);
            }
            //CASE2:前置条件cells还未初始化 as为null
            //条件一：true 表示当前未加锁
            //条件二：cells == as ？ 因为其它线程可能会在你给as赋值之后修改了cells
            //条件三：true 表示获取锁成功 会把cellsBusy = 1
            //       false表示其它线程正在持有这把锁
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    //cells == as ? 防止其它线程已经初始化 当前线程再次初始化 导致丢失数据
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            //CASE3：
            //1.当前cellsBusy处于加锁状态 表示其它线程正在初始化cells 所以当前线程将数据加到base
            //2.cells被其他线程初始化之后 当前线程需要将数据累加到base
            else if (casBase(v = base, ((fn == null) ? v + x :
                                        fn.applyAsLong(v, x))))
                break;                          // Fall back on using base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            Cell[] as; Cell a; int n; long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs; int m, j;
                                if ((rs = cells) != null &&
                                    (m = rs.length) > 0 &&
                                    rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                               ((fn == null) ?
                                Double.doubleToRawLongBits
                                (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                (fn.applyAsDouble
                                 (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            }
            else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            else if (casBase(v = base,
                             ((fn == null) ?
                              Double.doubleToRawLongBits
                              (Double.longBitsToDouble(v) + x) :
                              Double.doubleToRawLongBits
                              (fn.applyAsDouble
                               (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
