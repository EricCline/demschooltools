# --- !Ups

ALTER TABLE TASK ALTER enabled DROP DEFAULT;

# --- !Downs

ALTER TABLE TASK ALTER enabled SET DEFAULT TRUE;

