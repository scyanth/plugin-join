# Join types guide

## Inner Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
        text innerFields["A + B fields"]
    style A fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style B fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style A,B fill:#dbff4d
    style innerFields color:#000000
```
### Matching rows between A and B.
#### SQL equivalent: 
```sql
SELECT *
FROM A
JOIN B ON A.column = B.column
```
---
## Full Outer Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
    style A fill:#ccff00,fill-opacity:0.7,stroke:#000000,color:#000000
    style B fill:#ccff00,fill-opacity:0.7,stroke:#000000,color:#000000
    style A,B fill:#dbff4d
```
### Rows from A and B, with matching or not.
#### SQL equivalent: 
```sql
SELECT *
FROM A
FULL JOIN B ON A.column = B.column
```
---
## Left Outer Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
    style A fill:#ccff00,fill-opacity:0.7,stroke:#000000,color:#000000
    style B fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style A,B fill:#dbff4d
```
### Rows from A, matching with B or not.
#### SQL equivalent: 
```sql
SELECT *
FROM A
LEFT JOIN B ON A.column = B.column
```
---
## Right Outer Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
    style A fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style B fill:#ccff00,fill-opacity:0.7,stroke:#000000,color:#000000
    style A,B fill:#dbff4d
```
### Rows from B, matching with A or not.
#### SQL equivalent: 
```sql
SELECT *
FROM A
RIGHT JOIN B ON A.column = B.column
```
---
## Left Anti Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
    style A fill:#ccff00,fill-opacity:0.7,stroke:#000000,color:#000000
    style B fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style A,B fill:#ffffff
```
### Rows from A with no match with B.
#### SQL equivalent:
```sql
SELECT *
FROM A
LEFT JOIN B ON A.column = B.column
WHERE B.column IS NULL
```
---
## Right Anti Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
    style A fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style B fill:#ccff00,fill-opacity:0.7,stroke:#000000,color:#000000
    style A,B fill:#ffffff
```
### Rows from B with no match with A.
#### SQL equivalent:
```sql
SELECT *
FROM A
RIGHT JOIN B ON A.column = B.column
WHERE A.column IS NULL
```
---
## Left Semi Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
        text leftSemiFields["A fields only"]
    style A fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style B fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style A,B fill:#dbff4d
    style leftSemiFields color:#000000
```
### Rows from A matching with B.
#### Similar to Inner Join but without containing data from B.
#### SQL equivalent:
```sql
SELECT *
FROM A
WHERE column IN (
    SELECT column
    FROM B
)
```
---
## Right Semi Join
```mermaid
venn-beta
    set A:10
    set B:10
    union A,B:3
        text rightSemiFields["B fields only"]
    style A fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style B fill:#ffffff,fill-opacity:0,stroke:#000000,color:#000000
    style A,B fill:#dbff4d
    style rightSemiFields color:#000000
```
### Rows from B matching with A.
#### Similar to Inner Join but without containing data from A.
#### SQL equivalent:
```sql
SELECT *
FROM B
WHERE column IN (
    SELECT column
    FROM A
)
```
