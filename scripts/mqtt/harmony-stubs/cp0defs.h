/* The one core-timer macro the runtime uses, as <cp0defs.h> would give it. */
#ifndef EVIA_HARMONY_STUBS_CP0DEFS_H
#define EVIA_HARMONY_STUBS_CP0DEFS_H
unsigned long evia_cp0_count(void);
#define _CP0_GET_COUNT() evia_cp0_count()
#endif
